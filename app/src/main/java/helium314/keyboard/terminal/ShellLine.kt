// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.terminal

/**
 * The command line as the IME sees it.
 *
 * Termux exposes no editable text to the IME: [android.view.inputmethod.InputConnection]
 * `getTextBeforeCursor()` always returns an empty string, because Termux hands out a
 * `BaseInputConnection` whose buffer is a fake `Editable` that it clears after every commit
 * (see `TerminalView.onCreateInputConnection`). The IME therefore reconstructs the line from the
 * keys it sends itself.
 *
 * Trust model: any key that can move the cursor or replace the line behind our back (arrows,
 * home/end, tab completion, history recall, a paste from another app) makes the buffer untrusted.
 * While untrusted the buffer is frozen and suggestions stay hidden until Enter starts a new line.
 * A missing suggestion is cheap; a suffix inserted at the wrong place is not.
 */
object ShellLine {
    private val buffer = StringBuilder()

    @Volatile
    private var trusted = true

    @Volatile
    private var untrustedReason = ""

    val isTrusted: Boolean get() = trusted
    val reason: String get() = untrustedReason

    @Synchronized
    fun value(): String = buffer.toString()

    @Synchronized
    fun append(text: CharSequence) {
        if (trusted) buffer.append(text)
    }

    @Synchronized
    fun deleteLast() {
        if (!trusted || buffer.isEmpty()) return
        var end = buffer.length - 1
        if (end > 0 && Character.isLowSurrogate(buffer[end]) && Character.isHighSurrogate(buffer[end - 1])) end--
        buffer.setLength(end)
    }

    @Synchronized
    fun markUntrusted(cause: String) {
        trusted = false
        untrustedReason = cause
    }

    /** A new prompt line: everything we know about the old one is void. */
    @Synchronized
    fun reset() {
        buffer.setLength(0)
        trusted = true
        untrustedReason = ""
    }
}
