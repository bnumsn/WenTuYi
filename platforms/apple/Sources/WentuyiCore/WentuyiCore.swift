import Foundation

public struct WentuyiContact: Equatable, Sendable {
    public var name: String
    public var publicKeyBase64URL: String
    public var verified: Bool

    public init(name: String, publicKeyBase64URL: String, verified: Bool = false) {
        self.name = name
        self.publicKeyBase64URL = publicKeyBase64URL
        self.verified = verified
    }
}

public enum WentuyiSendTarget: Equatable, Sendable {
    case sharedPassphrase
    case contact(WentuyiContact)

    public var displayName: String {
        switch self {
        case .sharedPassphrase:
            return "共享密钥"
        case .contact(let contact):
            return contact.verified ? contact.name : "\(contact.name)（未验证）"
        }
    }
}

public enum WentuyiAction: Sendable {
    case plainText
    case encryptedText
    case encryptedQrText
}

public enum WentuyiPlatformError: Error, LocalizedError {
    case emptyInput
    case cryptoBackendMissing
    case unsupportedImageCommit
    case unsupportedQrRendering

    public var errorDescription: String? {
        switch self {
        case .emptyInput:
            return "输入框没有文字"
        case .cryptoBackendMissing:
            return "缺少文图易加密后端"
        case .unsupportedImageCommit:
            return "当前平台外壳不支持直接插入图片"
        case .unsupportedQrRendering:
            return "当前平台外壳不支持直接生成二维码图片"
        }
    }
}

public protocol WentuyiCryptoBackend: Sendable {
    func encryptText(_ text: String, target: WentuyiSendTarget) async throws -> String
    func decryptText(_ payload: String, target: WentuyiSendTarget) async throws -> String
}

public struct WentuyiKeyboardModel: Sendable {
    public var target: WentuyiSendTarget
    public var contacts: [WentuyiContact]

    public init(target: WentuyiSendTarget = .sharedPassphrase, contacts: [WentuyiContact] = []) {
        self.target = target
        self.contacts = contacts
    }

    public mutating func cycleTarget() -> WentuyiSendTarget {
        guard !contacts.isEmpty else {
            target = .sharedPassphrase
            return target
        }
        let allTargets: [WentuyiSendTarget] = [.sharedPassphrase] + contacts.map { .contact($0) }
        let current = allTargets.firstIndex(of: target) ?? 0
        target = allTargets[(current + 1) % allTargets.count]
        return target
    }
}

public actor WentuyiKeyboardController {
    public private(set) var model: WentuyiKeyboardModel
    private let crypto: WentuyiCryptoBackend?

    public init(model: WentuyiKeyboardModel = WentuyiKeyboardModel(), crypto: WentuyiCryptoBackend?) {
        self.model = model
        self.crypto = crypto
    }

    public func updateContacts(_ contacts: [WentuyiContact]) {
        model.contacts = contacts
        if case .contact(let selected) = model.target,
           !contacts.contains(where: { $0.publicKeyBase64URL == selected.publicKeyBase64URL }) {
            model.target = .sharedPassphrase
        }
    }

    @discardableResult
    public func cycleTarget() -> WentuyiSendTarget {
        model.cycleTarget()
    }

    public func encryptedText(for input: String) async throws -> String {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw WentuyiPlatformError.emptyInput }
        guard let crypto else { throw WentuyiPlatformError.cryptoBackendMissing }
        return try await crypto.encryptText(input, target: model.target)
    }
}

public final class UnconfiguredWentuyiCryptoBackend: WentuyiCryptoBackend {
    public init() {}

    public func encryptText(_ text: String, target: WentuyiSendTarget) async throws -> String {
        throw WentuyiPlatformError.cryptoBackendMissing
    }

    public func decryptText(_ payload: String, target: WentuyiSendTarget) async throws -> String {
        throw WentuyiPlatformError.cryptoBackendMissing
    }
}
