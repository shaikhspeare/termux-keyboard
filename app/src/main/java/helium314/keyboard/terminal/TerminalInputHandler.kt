// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.terminal

import android.view.KeyEvent
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.common.Constants

/**
 * Handles input while a terminal app is focused.
 *
 * Everything a terminal needs is a byte stream, so in terminal mode the IME stops using the word
 * composer entirely: printable events are committed directly, editing keys become the corresponding
 * key events, and the tracked line ([ShellLine]) is updated in the same step. That keeps the
 * terminal in sync with exactly one commit per key press and avoids composing text, which Termux
 * does not render until it is committed anyway.
 *
 * Returns `false` for mode keys (shift, symbols, ...) so the keyboard's own state machine keeps
 * working.
 */
class TerminalInputHandler(
    private val latinIME: LatinIME,
    private val connection: RichInputConnection,
) {
    fun handle(event: Event): Boolean {
        if (!TerminalMode.isActive || event.isGesture || event.isConsumed) return false

        val keyCode = event.keyCode
        when (keyCode) {
            KeyCode.SHIFT, KeyCode.CAPS_LOCK, KeyCode.CTRL, KeyCode.ALT, KeyCode.FN,
            KeyCode.ALPHA, KeyCode.SYMBOL, KeyCode.SYMBOL_ALPHA -> return false
        }

        val metaState = event.metaState
        if (metaState and KeyEvent.META_CTRL_ON != 0) return handleControl(event)
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            // Alt+key is ESC+key in a terminal; we cannot model the result, so stop trusting the line.
            sendKey(KeyEvent.KEYCODE_ESCAPE)
            ShellLine.markUntrusted("Alt")
        }

        when (keyCode) {
            KeyCode.DELETE -> return act { sendKey(KeyEvent.KEYCODE_DEL); ShellLine.deleteLast() }
            KeyCode.DELETE_WORD -> return act {
                sendControl(0x17) // Ctrl-W
                ShellLine.markUntrusted("word delete")
            }
            KeyCode.TAB -> return act {
                sendKey(KeyEvent.KEYCODE_TAB)
                ShellLine.markUntrusted("Tab completion")
            }
            KeyCode.ESCAPE -> return act {
                sendKey(KeyEvent.KEYCODE_ESCAPE)
                ShellLine.markUntrusted("Esc")
            }
            KeyCode.ARROW_LEFT -> return act {
                sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
                ShellLine.markUntrusted("cursor key")
            }
            KeyCode.ARROW_RIGHT -> return act {
                sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                ShellLine.markUntrusted("cursor key")
            }
            KeyCode.ARROW_UP -> return act {
                sendKey(KeyEvent.KEYCODE_DPAD_UP)
                ShellLine.markUntrusted("history recall")
            }
            KeyCode.ARROW_DOWN -> return act {
                sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
                ShellLine.markUntrusted("history recall")
            }
            KeyCode.MOVE_START_OF_LINE, KeyCode.MOVE_START_OF_PAGE -> return act {
                sendKey(KeyEvent.KEYCODE_MOVE_HOME)
                ShellLine.markUntrusted("cursor key")
            }
            KeyCode.MOVE_END_OF_LINE, KeyCode.MOVE_END_OF_PAGE -> return act {
                sendKey(KeyEvent.KEYCODE_MOVE_END)
                ShellLine.markUntrusted("cursor key")
            }
            KeyCode.PAGE_UP -> return act {
                sendKey(KeyEvent.KEYCODE_PAGE_UP)
                ShellLine.markUntrusted("paging")
            }
            KeyCode.PAGE_DOWN -> return act {
                sendKey(KeyEvent.KEYCODE_PAGE_DOWN)
                ShellLine.markUntrusted("paging")
            }
        }

        val text = event.textToCommit
        if (text.isNullOrEmpty()) return false
        if (text.length == 1 && text[0] == Constants.CODE_ENTER.toChar()) return act { enter() }

        return act {
            connection.commitText(text, 1)
            ShellLine.append(text)
        }
    }

    /** Multi-character input from the keyboard itself (clipboard paste, timestamp, ...). */
    fun handleText(text: CharSequence): Boolean {
        if (!TerminalMode.isActive || text.isEmpty()) return false
        if (text.length == 1 && text[0] == Constants.CODE_ENTER.toChar()) {
            act { enter() }
        } else {
            act {
                connection.commitText(text, 1)
                ShellLine.append(text)
            }
        }
        return true
    }

    /**
     * Suffix-only accept: the terminal already holds everything the user typed, so only the missing
     * tail is sent. Never delete-and-retype — backspace semantics differ across line editors.
     */
    fun acceptSuggestion(word: String) {
        val line = ShellLine.value()
        if (word.length <= line.length || !word.startsWith(line)) return
        val suffix = word.substring(line.length)
        connection.commitText(suffix, 1)
        ShellLine.append(suffix)
        refresh()
    }

    fun refresh() {
        latinIME.setSuggestions(ShellSuggestions.asSuggestedWords(ShellLine.value()))
    }

    private inline fun act(block: () -> Unit): Boolean {
        block()
        refresh()
        return true
    }

    private fun enter() {
        connection.commitText("\n", 1)
        ShellLine.reset()
    }

    private fun sendKey(keyCode: Int) {
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun sendControl(code: Int) = connection.commitText(code.toChar().toString(), 1)

    private fun handleControl(event: Event): Boolean {
        val char = event.textToCommit?.firstOrNull()?.lowercaseChar() ?: return false
        val code = when (char) {
            'a' -> 0x01
            'c' -> 0x03
            'd' -> 0x04
            'e' -> 0x05
            'k' -> 0x0b
            'l' -> 0x0c
            'u' -> 0x15
            'w' -> 0x17
            'z' -> 0x1a
            else -> return false
        }
        sendControl(code)
        when (char) {
            // these either abandon the line or start a fresh screen; a new prompt follows
            'c', 'd', 'l', 'u' -> ShellLine.reset()
            else -> ShellLine.markUntrusted("Ctrl-${char.uppercaseChar()}")
        }
        refresh()
        return true
    }
}
