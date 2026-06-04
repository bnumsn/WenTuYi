#if os(iOS) && canImport(UIKit)
import UIKit
import WentuyiCore

open class WentuyiKeyboardViewController: UIInputViewController {
    public var wentuyiController = WentuyiKeyboardController(crypto: UnconfiguredWentuyiCryptoBackend())

    private let statusLabel = UILabel()
    private let candidateRow = UIStackView()
    private let keyRows = UIStackView()

    open override func viewDidLoad() {
        super.viewDidLoad()
        buildKeyboard()
    }

    private func buildKeyboard() {
        view.backgroundColor = UIColor.systemGray6

        let root = UIStackView()
        root.axis = .vertical
        root.spacing = 6
        root.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 6),
            root.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -6),
            root.topAnchor.constraint(equalTo: view.topAnchor, constant: 6),
            root.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -6),
        ])

        candidateRow.axis = .horizontal
        candidateRow.spacing = 6
        root.addArrangedSubview(candidateRow)
        candidateRow.addArrangedSubview(makeButton("切", action: #selector(nextKeyboard)))
        candidateRow.addArrangedSubview(statusLabel)
        candidateRow.addArrangedSubview(makeButton("密文", action: #selector(encryptCurrentText)))
        candidateRow.addArrangedSubview(makeButton("目标", action: #selector(cycleTarget)))

        statusLabel.text = "共享密钥"
        statusLabel.textAlignment = .center
        statusLabel.font = UIFont.systemFont(ofSize: 13)
        statusLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)

        keyRows.axis = .vertical
        keyRows.spacing = 5
        root.addArrangedSubview(keyRows)
        for row in ["qwertyuiop", "asdfghjkl", "zxcvbnm"] {
            let stack = UIStackView()
            stack.axis = .horizontal
            stack.spacing = 4
            keyRows.addArrangedSubview(stack)
            for char in row { stack.addArrangedSubview(makeTextButton(String(char))) }
        }

        let controls = UIStackView()
        controls.axis = .horizontal
        controls.spacing = 4
        keyRows.addArrangedSubview(controls)
        controls.addArrangedSubview(makeButton("空格", action: #selector(space)))
        controls.addArrangedSubview(makeButton("⌫", action: #selector(backspace)))
        controls.addArrangedSubview(makeButton("换行", action: #selector(newline)))
    }

    private func makeTextButton(_ text: String) -> UIButton {
        let button = makeButton(text, action: #selector(insertCharacter(_:)))
        button.accessibilityIdentifier = text
        return button
    }

    private func makeButton(_ title: String, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = UIFont.systemFont(ofSize: 17, weight: .medium)
        button.backgroundColor = UIColor.white
        button.layer.cornerRadius = 6
        button.addTarget(self, action: action, for: .touchUpInside)
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 38).isActive = true
        return button
    }

    @objc private func insertCharacter(_ sender: UIButton) {
        guard let text = sender.accessibilityIdentifier else { return }
        textDocumentProxy.insertText(text)
    }

    @objc private func space() { textDocumentProxy.insertText(" ") }
    @objc private func newline() { textDocumentProxy.insertText("\n") }
    @objc private func backspace() { textDocumentProxy.deleteBackward() }
    @objc private func nextKeyboard() { advanceToNextInputMode() }

    @objc private func cycleTarget() {
        Task { @MainActor in
            let target = await wentuyiController.cycleTarget()
            statusLabel.text = target.displayName
        }
    }

    @objc private func encryptCurrentText() {
        let context = (textDocumentProxy.selectedText ?? textDocumentProxy.documentContextBeforeInput ?? "")
        Task { @MainActor in
            do {
                let payload = try await wentuyiController.encryptedText(for: context)
                if textDocumentProxy.selectedText == nil {
                    for _ in context { textDocumentProxy.deleteBackward() }
                }
                textDocumentProxy.insertText(payload)
                statusLabel.text = "已写入密文"
            } catch {
                statusLabel.text = error.localizedDescription
            }
        }
    }
}
#endif
