package com.example.finsightai.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode

/** Shared, assertion-free plumbing for the end-to-end tests. */

fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 10_000) =
    waitUntil(timeoutMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.waitGone(tag: String, timeoutMs: Long = 10_000) =
    waitUntil(timeoutMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

fun ComposeTestRule.waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000) =
    waitUntil(timeoutMs) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

/**
 * Waits until a node matching [matcher] exists. Use this, not `runCatching { assert...() }`, to poll for
 * UI that changes asynchronously: the matcher runs on the UI thread inside the fetch, whereas reading a
 * node's semantics on the test thread races the main thread's draw ("Detected multithreaded access to
 * SnapshotStateObserver").
 */
fun ComposeTestRule.waitForNode(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) =
    waitUntil(timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

/** Matches a node whose text (joined if it has several runs) starts with [prefix]. */
fun hasTextStartingWith(prefix: String) = SemanticsMatcher("text starts with '$prefix'") { node ->
    node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString("") { it.text }?.startsWith(prefix) == true
}

fun ComposeTestRule.hasTag(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.openTab(name: String) {
    onNodeWithTag("tab_$name").performClick()
    waitForIdle()
}

/**
 * Like [scrollToTag] for content that appears asynchronously (a network result, a sign-in): keeps trying
 * until the child exists in the lazy list, then returns it.
 */
fun ComposeTestRule.scrollUntilTag(list: String, tag: String, timeoutMs: Long = 15_000): SemanticsNodeInteraction {
    waitUntil(timeoutMs) { runCatching { onNodeWithTag(list).performScrollToNode(hasTestTag(tag)) }.isSuccess }
    return onNodeWithTag(tag)
}

/** Scrolls the lazy list tagged [list] until a child tagged [tag] exists, then returns it. */
fun ComposeTestRule.scrollToTag(list: String, tag: String): SemanticsNodeInteraction {
    onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
    return onNodeWithTag(tag)
}

fun ComposeTestRule.scrollToText(list: String, text: String, substring: Boolean = false) {
    onNodeWithTag(list).performScrollToNode(hasText(text, substring = substring))
}

/** The plain text of the node tagged [tag] (joined if it has several runs). */
fun ComposeTestRule.textOf(tag: String): String =
    onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

/** Matches a card tagged [tag] that has a descendant showing [text]. */
fun cardWithText(tag: String, text: String, substring: Boolean = false) =
    hasTestTag(tag) and hasAnyDescendant(hasText(text, substring = substring))

fun ComposeTestRule.assertCardShows(tag: String, text: String, substring: Boolean = false) {
    onNode(cardWithText(tag, text, substring)).assertIsDisplayed()
}

/** Counts nodes whose test tag starts with [prefix]. */
fun ComposeTestRule.countTagsStartingWith(prefix: String): Int =
    onAllNodes(SemanticsMatcher("tag starts with $prefix") { node ->
        SemanticsProperties.TestTag in node.config && node.config[SemanticsProperties.TestTag].startsWith(prefix)
    }).fetchSemanticsNodes().size
