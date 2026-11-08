// JNI bridge between FinSight's Kotlin layer and llama.cpp.
//
// One `Session` owns a loaded model + context. Kotlin serialises access to a session (a single
// generation at a time) and passes the opaque handle back and forth as a jlong.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>
#include <unistd.h>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

#define LOG_TAG "finsight-llm"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Result codes shared with LlamaNative.kt.
constexpr jint RC_OK = 0;
constexpr jint RC_LOAD_FAILED = 1;
constexpr jint RC_CONTEXT_FAILED = 2;
constexpr jint RC_PROMPT_TOO_LONG = 3;
constexpr jint RC_DECODE_FAILED = 4;
constexpr jint RC_CANCELLED = 5;
constexpr jint RC_BAD_HANDLE = 6;

constexpr int BATCH_SIZE = 512;
constexpr int MAX_PENDING_PIECES = 4;

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    common_chat_templates_ptr templates;
    int n_ctx = 0;
};

bool g_backend_ready = false;

void android_log_callback(ggml_log_level level, const char *text, void * /*user*/) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN: prio = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: prio = ANDROID_LOG_INFO; break;
    }
    __android_log_print(prio, LOG_TAG, "%s", text);
}

bool is_valid_utf8(const std::string &s) {
    const auto *bytes = reinterpret_cast<const unsigned char *>(s.data());
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        int len;
        if ((bytes[i] & 0x80) == 0x00) len = 1;
        else if ((bytes[i] & 0xE0) == 0xC0) len = 2;
        else if ((bytes[i] & 0xF0) == 0xE0) len = 3;
        else if ((bytes[i] & 0xF8) == 0xF0) len = 4;
        else return false;
        if (i + len > n) return false;
        for (int k = 1; k < len; ++k) {
            if ((bytes[i + k] & 0xC0) != 0x80) return false;
        }
        i += len;
    }
    return true;
}

int pick_threads() {
    const int cores = (int) sysconf(_SC_NPROCESSORS_ONLN);
    return std::max(2, std::min(6, cores - 2));
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_example_finsightai_llm_LlamaNative_nativeInit(JNIEnv *env, jobject, jstring jlib_dir) {
    if (g_backend_ready) return;
    llama_log_set(android_log_callback, nullptr);
    const char *dir = env->GetStringUTFChars(jlib_dir, nullptr);
    LOGi("Loading ggml backends from %s", dir);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(jlib_dir, dir);
    llama_backend_init();
    g_backend_ready = true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_finsightai_llm_LlamaNative_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// Loads the model and creates a context. Returns 0 and writes the handle to out_handle[0], or an RC_* code.
extern "C" JNIEXPORT jint JNICALL
Java_com_example_finsightai_llm_LlamaNative_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx,
                                                       jlongArray out_handle) {
    llama_model_params mparams = llama_model_default_params();

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LOGi("Loading model %s", path);
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return RC_LOAD_FAILED;

    const int trained = llama_model_n_ctx_train(model);
    const int ctx_size = std::min<int>(n_ctx, trained > 0 ? trained : n_ctx);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = ctx_size;
    cparams.n_batch = BATCH_SIZE;
    cparams.n_ubatch = BATCH_SIZE;
    const int threads = pick_threads();
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    LOGi("Context: n_ctx=%d threads=%d", ctx_size, threads);

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return RC_CONTEXT_FAILED;
    }

    auto *session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->n_ctx = ctx_size;
    session->templates = common_chat_templates_init(model, "");

    jlong handle = reinterpret_cast<jlong>(session);
    env->SetLongArrayRegion(out_handle, 0, 1, &handle);
    return RC_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_finsightai_llm_LlamaNative_nativeRelease(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (!session) return;
    session->templates.reset();
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}

// Generates a reply for (system, user), streaming UTF-8 text pieces to sink.onToken(byte[]): Boolean.
// Returning false from the sink cancels generation.
extern "C" JNIEXPORT jint JNICALL
Java_com_example_finsightai_llm_LlamaNative_nativeGenerate(JNIEnv *env, jobject, jlong handle, jstring jsystem,
                                                           jstring juser, jint max_tokens, jfloat temperature,
                                                           jobject sink) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (!session || !session->ctx) return RC_BAD_HANDLE;

    const char *system_c = env->GetStringUTFChars(jsystem, nullptr);
    const char *user_c = env->GetStringUTFChars(juser, nullptr);
    std::string system_text(system_c);
    std::string user_text(user_c);
    env->ReleaseStringUTFChars(jsystem, system_c);
    env->ReleaseStringUTFChars(juser, user_c);

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_token = env->GetMethodID(sink_class, "onToken", "([B)Z");
    if (!on_token) return RC_BAD_HANDLE;

    // 1. Render the chat template (Jinja) into a prompt string.
    common_chat_templates_inputs inputs;
    if (!system_text.empty()) {
        common_chat_msg sys;
        sys.role = "system";
        sys.content = system_text;
        inputs.messages.push_back(sys);
    }
    common_chat_msg usr;
    usr.role = "user";
    usr.content = user_text;
    inputs.messages.push_back(usr);
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;
    inputs.enable_thinking = false; // short, direct answers: no chain-of-thought preamble

    std::string prompt;
    try {
        prompt = common_chat_templates_apply(session->templates.get(), inputs).prompt.str();
    } catch (const std::exception &e) {
        LOGw("chat template failed (%s); falling back to a plain prompt", e.what());
        prompt = system_text + "\n\n" + user_text + "\n";
    }

    // 2. Tokenize and make sure everything fits.
    llama_tokens tokens = common_tokenize(session->ctx, prompt, /*add_special=*/true, /*parse_special=*/true);
    if ((int) tokens.size() + 8 >= session->n_ctx) {
        LOGw("Prompt too long: %d tokens for n_ctx=%d", (int) tokens.size(), session->n_ctx);
        return RC_PROMPT_TOO_LONG;
    }
    const int budget = std::min<int>(max_tokens, session->n_ctx - (int) tokens.size() - 4);

    // 3. Fresh KV cache + sampler per request: requests are independent.
    llama_memory_clear(llama_get_memory(session->ctx), false);
    common_params_sampling sparams;
    sparams.temp = temperature;
    common_sampler *sampler = common_sampler_init(session->model, sparams);

    common_batch batch(session->ctx);
    jint rc = RC_OK;

    // 4. Prompt prefill in chunks, logits only for the last token.
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int n = std::min<int>((int) tokens.size() - i, BATCH_SIZE);
        batch.clear();
        for (int j = 0; j < n; ++j) {
            batch.add(tokens[i + j], i + j, 0, i + j == (int) tokens.size() - 1);
        }
        if (llama_process(session->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            rc = RC_DECODE_FAILED;
            break;
        }
    }

    // 5. Sample loop.
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    std::string pending;
    int pending_pieces = 0;
    // Hands pending bytes to Kotlin, which decodes them (replacing malformed sequences). False: stop.
    auto flush = [&]() -> bool {
        jbyteArray piece = env->NewByteArray((jsize) pending.size());
        if (!piece) {
            env->ExceptionClear();
            return false;
        }
        env->SetByteArrayRegion(piece, 0, (jsize) pending.size(), reinterpret_cast<const jbyte *>(pending.data()));
        const jboolean keep_going = env->CallBooleanMethod(sink, on_token, piece);
        env->DeleteLocalRef(piece);
        pending.clear();
        pending_pieces = 0;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep_going;
    };
    int pos = (int) tokens.size();
    for (int produced = 0; rc == RC_OK && produced < budget; ++produced) {
        const llama_token tok = common_sampler_sample(sampler, session->ctx, -1);
        common_sampler_accept(sampler, tok, true);
        if (llama_vocab_is_eog(vocab, tok)) break;

        pending += common_token_to_piece(session->ctx, tok);
        ++pending_pieces;
        if ((is_valid_utf8(pending) || pending_pieces >= MAX_PENDING_PIECES) && !flush()) {
            rc = RC_CANCELLED;
            break;
        }

        batch.clear();
        batch.add(tok, pos++, 0, true);
        if (llama_process(session->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            rc = RC_DECODE_FAILED;
        }
    }

    if (rc == RC_OK && !pending.empty() && !flush()) rc = RC_CANCELLED;

    common_sampler_free(sampler);
    return rc;
}
