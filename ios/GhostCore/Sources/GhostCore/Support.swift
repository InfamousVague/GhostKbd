import Foundation

// Small helpers shared by the ported files. Nothing here is part of the keyboard's behaviour.

extension String {
    /// Java's String.hashCode (over UTF-16), so ids hash as they do on Android.
    var javaHashCode: Int32 {
        var h: Int32 = 0
        for u in utf16 { h = h &* 31 &+ Int32(u) }
        return h
    }

    var isBlank: Bool { allSatisfy { $0.isWhitespace } }

    /// The last `n` characters.
    func takeLast(_ n: Int) -> String { String(suffix(n)) }

    /// The first `n` characters.
    func take(_ n: Int) -> String { String(prefix(n)) }

    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// A lock-guarded value, for the few fields read off the main thread.
final class Locked<T> {
    private var value: T
    private let lock = NSLock()
    init(_ value: T) { self.value = value }
    func get() -> T { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ v: T) { lock.lock(); value = v; lock.unlock() }
    func with<R>(_ body: (inout T) -> R) -> R { lock.lock(); defer { lock.unlock() }; return body(&value) }
}

enum Json {
    static func object(_ data: Data) -> [String: Any]? { (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] }
    static func object(_ text: String) -> [String: Any]? { object(Data(text.utf8)) }
    static func array(_ data: Data) -> [Any]? { (try? JSONSerialization.jsonObject(with: data)) as? [Any] }
    static func array(_ text: String) -> [Any]? { array(Data(text.utf8)) }

    static func data(_ value: Any, pretty: Bool = false) -> Data {
        var options: JSONSerialization.WritingOptions = [.withoutEscapingSlashes]
        if pretty { options.insert(.prettyPrinted); options.insert(.sortedKeys) }
        return (try? JSONSerialization.data(withJSONObject: value, options: options)) ?? Data("{}".utf8)
    }

    static func string(_ value: Any, pretty: Bool = false) -> String { String(decoding: data(value, pretty: pretty), as: UTF8.self) }

    /// org.json's optString: a string as it is, a number or boolean as text, "" where there is none.
    static func str(_ v: Any?) -> String {
        switch v {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return ""
        }
    }

    static func objects(_ v: Any?) -> [[String: Any]] { (v as? [Any])?.compactMap { $0 as? [String: Any] } ?? [] }

    static func double(_ v: Any?) -> Double? { (v as? NSNumber)?.doubleValue ?? (v as? String).flatMap(Double.init) }
    static func int(_ v: Any?) -> Int? { (v as? NSNumber)?.intValue ?? (v as? String).flatMap(Int.init) }
}

/// A blocking HTTP round trip on `session`, for the worker queues. Never call it on the main thread.
enum Http {
    struct Response { let status: Int; let data: Data }

    static func send(_ request: URLRequest, session: URLSession) -> Result<Response, Error> {
        let done = DispatchSemaphore(value: 0)
        var result: Result<Response, Error> = .failure(URLError(.unknown))
        let task = session.dataTask(with: request) { data, response, error in
            if let error = error { result = .failure(error) }
            else { result = .success(Response(status: (response as? HTTPURLResponse)?.statusCode ?? 0, data: data ?? Data())) }
            done.signal()
        }
        task.resume()
        done.wait()
        return result
    }
}
