import Foundation

#if canImport(Security)
import Security
#endif

public actor StableIdentityStore {
    private let defaults: UserDefaults
    private let keychainKey: String

    public init(
        defaults: UserDefaults = .standard,
        keychainKey: String = "com.motocom.device-id"
    ) {
        self.defaults = defaults
        self.keychainKey = keychainKey
    }

    public func deviceID() -> String {
        #if canImport(Security)
        if let existing = readKeychainValue(), isCanonicalUUID(existing) {
            return existing
        }
        #endif
        if let existing = defaults.string(forKey: keychainKey), isCanonicalUUID(existing) {
            return existing
        }
        let value = UUID().uuidString.lowercased()
        #if canImport(Security)
        saveKeychainValue(value)
        #endif
        defaults.set(value, forKey: keychainKey)
        return value
    }

    private func isCanonicalUUID(_ value: String) -> Bool {
        guard let uuid = UUID(uuidString: value) else { return false }
        return uuid.uuidString.lowercased() == value
    }

    public func newRuntimeSessionID() -> String {
        UUID().uuidString.lowercased()
    }

    #if canImport(Security)
    private func readKeychainValue() -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: keychainKey,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func saveKeychainValue(_ value: String) {
        let data = Data(value.utf8)
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: keychainKey,
            kSecValueData: data,
            kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let match: [CFString: Any] = [
                kSecClass: kSecClassGenericPassword,
                kSecAttrAccount: keychainKey
            ]
            SecItemUpdate(match as CFDictionary, [kSecValueData: data] as CFDictionary)
        }
    }
    #endif
}
