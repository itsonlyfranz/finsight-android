# Curated model catalog: live Hub verification (2026-11-08)

Checked with `curl https://huggingface.co/api/models/<repo>?blobs=true` and `/api/models/<repo>/tree/main`
(byte size = `size`, sha256 = `lfs.oid`). Runtime: upstream llama.cpp v0.9.0 (archs gemma4, lfm3, qwen3, qwen35; ggml types Q1_0/Q2_0 present with CPU kernels).
Download redirect verified: `huggingface.co/<repo>/resolve/main/<file>` -> 302 to `us.aws.cdn.hf.co` (different host), `Accept-Ranges: bytes`, 206 on Range.

| Family | Repo | File | Bytes | gated (Hub) | License | gguf arch | sha256 |
|---|---|---|---|---|---|---|---|
| Gemma 4 | google/gemma-4-E2B-it-qat-q4_0-gguf | gemma-4-E2B_q4_0-it.gguf | 3,349,516,256 | false | apache-2.0 | gemma4 | fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634 |
| Gemma 4 | ggml-org/gemma-4-E2B-it-GGUF | gemma-4-E2B-it-Q4_0.gguf | 2,841,481,184 | false | apache-2.0 | gemma4 | 8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52 |
| Bonsai | prism-ml/Bonsai-1.7B-gguf | Bonsai-1.7B-Q1_0.gguf | 248,302,272 | false | apache-2.0 | qwen3 | 3d7c6c90dd98717a203adb22d5eacd2581850e40aa5327e144b97766cae5f7e3 |
| Bonsai | prism-ml/Ternary-Bonsai-2-2B-gguf | Ternary-Bonsai-2-2B-Q2_0.gguf | 546,689,504 | false | apache-2.0 | qwen35 | c2893fa6f951daee8b7263d5f49b455e57989ef56417a22125b03566dc560650 |
| LFM 3 | LiquidAI/LFM3-1.7B-GGUF | LFM3-1.7B-Q4_K_M.gguf | 1,099,627,808 | false | other (LFM Open License) | lfm3 | 3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4 |

Notes
- Gemma 4 repos are NOT gated on the Hub today (`gated: false`), although the brief expected gated. The optional HF token path is still implemented and tested for repos that are gated.
- Not offered: Ternary-Bonsai `PQ2_0`/`PTQ1_0` (need the PrismML llama.cpp fork; upstream rejects them), Gemma 4 E4B (5.2 GB > 4 GiB phone limit), LiteRT-LM `.litertlm` (different runtime).
- RAM guidance = file size x1.2 + 512 MB (min) / x1.5 + 1 GB (recommended); mmap'd weights + KV cache at ~4k context.
- Search API used: `/api/models?search=..&filter=gguf&sort=downloads&direction=-1&limit=..&expand[]=gguf&expand[]=gated&expand[]=downloads&expand[]=likes&expand[]=pipeline_tag&expand[]=tags&expand[]=library_name` (live-verified; returns `gguf.architecture`, `gated`, `pipeline_tag`, `tags`). Unauthenticated `/tree/main` lists LFS sizes and oids.
- Live-captured test fixtures: `app/src/test/resources/hf/*.json`.
