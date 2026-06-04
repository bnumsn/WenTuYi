#if os(macOS) && canImport(InputMethodKit)
import Foundation
import InputMethodKit
import WentuyiCore

open class WentuyiInputController: IMKInputController {
    public var wentuyiController = WentuyiKeyboardController(crypto: UnconfiguredWentuyiCryptoBackend())
    private var buffer = ""

    open override func inputText(_ string: String!, key keyCode: Int, modifiers flags: Int, client sender: Any!) -> Bool {
        guard let string else { return false }
        if string == "\r" || string == "\n" {
            commitBuffer(to: sender)
            return true
        }
        if string == "\u{1b}" {
            buffer.removeAll()
            return true
        }
        buffer.append(string)
        senderInsert(sender, text: string)
        return true
    }

    public func encryptCurrentBuffer(client sender: Any!) {
        let text = buffer
        Task { [weak self] in
            guard let self else { return }
            do {
                let payload = try await self.wentuyiController.encryptedText(for: text)
                self.buffer.removeAll()
                self.senderInsert(sender, text: payload)
            } catch {
                self.senderInsert(sender, text: "[文图易错误: \(error.localizedDescription)]")
            }
        }
    }

    private func commitBuffer(to sender: Any!) {
        senderInsert(sender, text: "\n")
        buffer.removeAll()
    }

    private func senderInsert(_ sender: Any!, text: String) {
        if let client = sender as? IMKTextInput {
            client.insertText(text, replacementRange: NSRange(location: NSNotFound, length: NSNotFound))
        }
    }
}
#endif
