import Foundation

/// Ghostwriter's GitHub search: the commits behind "<<posthog updates we made>>".
///
/// The request's own words (less the asking words: summarize, updates, we, made…) are searched for in the commit
/// messages of the repositories the person owns and the organizations they belong to, newest first. Searched by owner
/// rather than author, because many commits are authored by an AI agent rather than the person. "today", "yesterday",
/// "this week", "last week" and "this month" narrow the dates. With the person's own token, straight to GitHub.
///
/// `account` and `search` block on the network: run them off the main thread (Ghostwriter does).
public final class GitHubCommits {
    public struct Commit: Hashable {
        public let repo: String
        /// "yyyy-MM-dd".
        public let date: String
        public let message: String
        public init(repo: String, date: String, message: String) { self.repo = repo; self.date = date; self.message = message }
    }

    public struct Account: Hashable {
        public let login: String
        public let owners: [String]
        public init(login: String, owners: [String]) { self.login = login; self.owners = owners }
    }

    public struct GitHubError: Error, LocalizedError, Equatable {
        public let message: String
        public init(_ message: String) { self.message = message }
        public var errorDescription: String? { message }
    }

    public static let api = "https://api.github.com"
    // The cache: a hash of the token (not the token), the login and the owners.
    static let cacheToken = "github_cache_token"
    static let cacheLogin = "github_cache_login"
    static let cacheOwners = "github_cache_owners"

    private let store: PrefsStore
    private let session: URLSession

    /// `store` keeps the account cache (login and owners, per token); `session` carries the requests.
    public init(store: PrefsStore, session: URLSession = .shared) {
        self.store = store
        self.session = session
    }

    /// The token's account and the owners searched (the account, then its organizations), cached per token.
    public func account(token: String, refresh: Bool = false) throws -> Account {
        let tag = String(token.javaHashCode)
        if !refresh, (store.object(forKey: GitHubCommits.cacheToken) as? String) == tag {
            let login = (store.object(forKey: GitHubCommits.cacheLogin) as? String) ?? ""
            if !login.isEmpty {
                let owners = ((store.object(forKey: GitHubCommits.cacheOwners) as? String) ?? "").split(separator: ",").map(String.init).filter { !$0.isBlank }
                return Account(login: login, owners: owners)
            }
        }
        let user = Json.object(try get("\(GitHubCommits.api)/user", token)) ?? [:]
        let login = Json.str(user["login"])
        if login.isEmpty { throw GitHubError("GitHub didn't say whose token this is") }
        let orgs = (try? get("\(GitHubCommits.api)/user/orgs?per_page=50", token)).flatMap { Json.array($0) } ?? []
        let owners = [login] + Json.objects(orgs).map { Json.str($0["login"]) }.filter { !$0.isBlank }
        store.set(tag, forKey: GitHubCommits.cacheToken)
        store.set(login, forKey: GitHubCommits.cacheLogin)
        store.set(owners.joined(separator: ","), forKey: GitHubCommits.cacheOwners)
        return Account(login: login, owners: owners)
    }

    /// Forgets the cached account (the token was removed).
    public func forgetAccount() {
        store.removeObject(forKey: GitHubCommits.cacheToken)
        store.removeObject(forKey: GitHubCommits.cacheLogin)
        store.removeObject(forKey: GitHubCommits.cacheOwners)
    }

    private static let asking: Set<String> = [
        "summarize", "summarise", "summary", "update", "updates", "we", "made", "make", "did", "done", "change", "changes",
        "changed", "recent", "recently", "latest", "new", "post", "write", "list", "tell", "about", "what", "which", "the",
        "and", "for", "our", "my", "me", "to", "of", "in", "on", "a", "an", "all", "any", "some", "with", "from", "that",
        "this", "these", "those", "is", "are", "was", "were", "been", "have", "has", "had", "do", "does", "please", "pls",
        "today", "yesterday", "week", "month", "last", "past", "since", "tldr", "standup", "work", "worked", "working",
        "commit", "commits", "pushed", "shipped", "release", "released", "notes", "status", "progress", "github", "repo",
        "how", "why", "when", "where", "who", "it", "its", "be", "by", "so", "up", "out", "into", "i", "you", "your",
        "then", "also", "just", "get", "give", "not", "no", "dont", "doesnt", "isnt", "didnt", "wont", "us", "them", "they", "can", "could", "should", "would", "team", "slack",
    ]

    private static let notKeyword = try! NSRegularExpression(pattern: "[^\\p{L}\\p{N}_.-]+")

    /// The words worth searching for: what the request is about, not how it asks.
    public static func keywords(_ request: String) -> [String] {
        let lower = request.lowercased()
        let spaced = notKeyword.stringByReplacingMatches(in: lower, range: NSRange(location: 0, length: lower.utf16.count), withTemplate: " ")
        var out: [String] = []
        for part in spaced.split(separator: " ") {
            let w = part.trimmingCharacters(in: CharacterSet(charactersIn: ".-"))
            if w.utf16.count < 2 || asking.contains(w) || w.unicodeScalars.allSatisfy({ $0.properties.generalCategory == .decimalNumber }) || out.contains(w) { continue }
            out.append(w)
            if out.count == 4 { break }
        }
        return out
    }

    private static func day(_ date: Date, _ calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// The earliest commit date ("yyyy-MM-dd") the request asks about, if it names a window.
    public static func since(_ request: String, today: Date = Date(), calendar: Calendar = Calendar(identifier: .gregorian)) -> String? {
        let r = request.lowercased()
        func back(_ component: Calendar.Component, _ n: Int) -> String { day(calendar.date(byAdding: component, value: -n, to: today) ?? today, calendar) }
        if r.contains("today") { return day(today, calendar) }
        if r.contains("yesterday") { return back(.day, 1) }
        if r.contains("last week") || r.contains("past week") { return back(.day, 14) }
        if r.contains("this week") {
            // Back to Monday.
            let weekday = calendar.component(.weekday, from: today)
            return back(.day, (weekday + 5) % 7)
        }
        if r.contains("this month") {
            var c = calendar.dateComponents([.year, .month], from: today)
            c.day = 1
            return day(calendar.date(from: c) ?? today, calendar)
        }
        if r.contains("last month") || r.contains("past month") { return back(.month, 2) }
        return nil
    }

    /// java.net.URLEncoder's form encoding.
    static func formEncode(_ text: String) -> String {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-*_ ")
        return (text.addingPercentEncoding(withAllowedCharacters: allowed) ?? text).replacingOccurrences(of: " ", with: "+")
    }

    /// The commit search for one word in one owner's repositories; `isUser` for the account itself, else an organization.
    public static func searchURL(word: String, owner: String, isUser: Bool, since: String?) -> URL {
        let q = "\(word) \(isUser ? "user" : "org"):\(owner)" + (since.map { " committer-date:>=\($0)" } ?? "")
        return URL(string: "\(api)/search/commits?q=\(formEncode(q))&sort=committer-date&order=desc&per_page=20")!
    }

    /// A GET to GitHub's API with the person's token.
    public static func buildRequest(url: URL, token: String) -> URLRequest {
        var r = URLRequest(url: url)
        r.timeoutInterval = 30
        r.cachePolicy = .reloadIgnoringLocalCacheData
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        r.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        r.setValue("GhostKeyboard", forHTTPHeaderField: "User-Agent")
        return r
    }

    /// The commits in a commit-search answer, each with its sha, in the order GitHub sent them.
    public static func parseSearch(_ data: Data) -> [(sha: String, commit: Commit)] {
        guard let items = Json.object(data)?["items"] as? [Any] else { return [] }
        var out: [(sha: String, commit: Commit)] = []
        for item in Json.objects(items) {
            guard let commit = item["commit"] as? [String: Any] else { continue }
            let date = Json.str((commit["committer"] as? [String: Any])?["date"]).take(10)
            let message = Json.str(commit["message"]).split(omittingEmptySubsequences: false, whereSeparator: { $0.isNewline })
                .filter { !$0.hasPrefix("Co-Authored-By") && !$0.hasPrefix("Claude-Session") }
                .joined(separator: "\n").trimmed.take(900)
            let repo = Json.str((item["repository"] as? [String: Any])?["full_name"])
            out.append((Json.str(item["sha"]), Commit(repo: repo, date: date, message: message)))
        }
        return out
    }

    /// GitHub's refusal as a short sentence.
    public static func errorMessage(status: Int, body: String) -> String {
        let message = Json.str(Json.object(body)?["message"])
        let lead: String
        switch status {
        case 401: lead = "GitHub refused the token"
        case 403, 429: lead = "GitHub's search limit: try again in a minute"
        default: lead = "GitHub said no (\(status))"
        }
        return lead + (!message.isBlank && status != 401 ? ": \(message.take(120))" : "")
    }

    /// Orders what the searches found: commits found for more words first (then newest), as many as fit in `budget`
    /// characters, then newest first. `hits` is every (sha, commit) found, a commit once for each word that found it.
    public static func rank(_ hits: [(sha: String, commit: Commit)], budget: Int = 9000) -> [Commit] {
        var order: [String] = []
        var found: [String: (commit: Commit, count: Int)] = [:]
        for h in hits {
            if let seen = found[h.sha] { found[h.sha] = (seen.commit, seen.count + 1) } else { found[h.sha] = (h.commit, 1); order.append(h.sha) }
        }
        let byCount = order.enumerated().map { (offset: $0.offset, hit: found[$0.element]!) }.sorted { a, b in
            if a.hit.count != b.hit.count { return a.hit.count > b.hit.count }
            if a.hit.commit.date != b.hit.commit.date { return a.hit.commit.date > b.hit.commit.date }
            return a.offset < b.offset
        }
        var left = budget
        var kept: [(offset: Int, commit: Commit)] = []
        for (i, e) in byCount.enumerated() {
            if left <= 0 { break }
            left -= e.hit.commit.message.utf16.count + 40
            kept.append((i, e.hit.commit))
        }
        return kept.sorted { a, b in a.commit.date != b.commit.date ? a.commit.date > b.commit.date : a.offset < b.offset }.map { $0.commit }
    }

    /// Commits matching the request, newest first, within `budget` characters. Each word is searched on its own
    /// across every owner, and the commits that turn up for more words come first. Empty when nothing matches.
    public func search(token: String, request: String, budget: Int = 9000, today: Date = Date()) throws -> [Commit] {
        let words = GitHubCommits.keywords(request)
        if words.isEmpty { return [] }
        let owners = try account(token: token).owners
        let window = GitHubCommits.since(request, today: today)
        var hits: [(sha: String, commit: Commit)] = []
        for word in words {
            for owner in owners.prefix(6) {
                let url = GitHubCommits.searchURL(word: word, owner: owner, isUser: owner == owners.first, since: window)
                guard let data = try? get(url.absoluteString, token) else { continue }
                hits += GitHubCommits.parseSearch(data)
            }
        }
        return GitHubCommits.rank(hits, budget: budget)
    }

    private func get(_ url: String, _ token: String) throws -> Data {
        guard let u = URL(string: url) else { throw GitHubError("GitHub said no (0)") }
        switch Http.send(GitHubCommits.buildRequest(url: u, token: token), session: session) {
        case .failure(let e):
            throw GitHubError("Couldn't reach GitHub: \(e.localizedDescription.isEmpty ? "no connection" : e.localizedDescription)")
        case .success(let r):
            if !(200...299).contains(r.status) { throw GitHubError(GitHubCommits.errorMessage(status: r.status, body: String(decoding: r.data, as: UTF8.self))) }
            return r.data
        }
    }
}
