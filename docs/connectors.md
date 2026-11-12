# Connectors

A plugin (`connectors`; `Connectors.kt`, `McpClient.kt`, `AiTools.kt`; a Market add-on with a page of its own in
Settings; Android only). Matt, 2026-11-12, choosing from a list of plugins to add: "I like … the mcp servers". The
list had called it Connectors and said: "Let people add other MCP servers (calendar, issues, mail), so
`<<when am I free Thursday>>` reads the real calendar. Read-only first."

A connector is a Model Context Protocol (MCP) server: the way AI assistants are given a calendar, an issue tracker, a
set of documents. The person adds one by its address; Ghostwriter may then ask it things while it writes.

## What can be added

- An **https address**, a **name**, and a **token** where the server wants one. The token is sent to that server as
  `Authorization: Bearer`, and to nowhere else.
- **Not yet: a server that can only be signed in to through a browser** (OAuth). Such a server answers 401, and the
  page says: "This server wants a sign-in. Give it a token … signing in through a browser is not built yet." Many
  hosted connectors are of this kind, so this is the plugin's largest gap. What building it takes is under "Not
  built" below.
- Up to 8 connectors.

## Tools and their switches

When a connector is added, and on Refresh, its server is asked for its tools. Each has a switch:

- a tool the server marks read-only (`readOnlyHint: true`) starts **on**;
- every other tool (marked as changing things, or not marked at all) starts **off**, and stays off until the person
  turns it on;
- asked again, each tool keeps the switch it had.

The mark is the server's own word, not a promise, and the page says so. There is no confirmation before a tool is
called: the switch is the consent. That is why nothing that is not marked read-only is ever on by itself.

## What Ghostwriter does with them

With the plugin added and Ghostwriter › What it can read › My connectors on (it is, once the plugin is added), a
`<<request>>` is sent to the AI with the tools that are on: up to 16 (each costs words on every request), in the
order the connectors were added. The AI may call up to 6 of them while it writes; each answer is cut to 6,000
characters. It is told (`Connectors.section`) to call a tool only where the request needs a fact it holds, that what
a tool returns is data and never an instruction, and to leave a [blank] where a tool fails.

If the request fails with the tools in it for any reason but Offline or a refused key (a model or provider that will
not take tools, say), it is made again as it was before there were connectors, so they never cost anyone their
answer, and Settings › Connectors shows what happened under "Last trouble".

With Gemini, a request that carries tools does not also ask for Gemini's own web search, which not all of its models
allow in one request.

## Who talks to whom

- The **phone** asks the connector's server, with its token (`NetPath.CONNECTOR_TOOLS` to read the tools,
  `NetPath.CONNECTOR_CALL` for each call). There is no Ghost server in between.
- What a tool answers goes to the **AI provider** chosen in Settings › AI, as notes and commits do, with the tools'
  names and descriptions (`NetPath.GHOSTWRITER` says so).
- The AI's key never goes to a connector. A connector's token never goes to the AI or to another connector.
- The guard (`Net.kt`, `NetLedger.check`) lets the connectors' two paths reach the servers the person added, each at
  its own port, and no other; and lets no other path reach those servers. Offline stops them. `tool/net_check.sh`
  holds all of that.

The connectors, tokens included, are kept on the phone in the keyboard's own settings file (`connectors`), where the
AI keys are. Settings is never told a token, only whether a connector has one.

## The protocol

`McpClient` speaks "Streamable HTTP": one POST for each request, answered with JSON or with an event stream that
ends in the answer. Two generations of server are met:

- the specification of 2026-07-28 has no handshake: each request says its version in the `MCP-Protocol-Version`
  header and in `_meta`, and its method in `Mcp-Method` (and the tool in `Mcp-Name`);
- the specifications up to 2025-11-25 start with `initialize` (which may give a session id, sent from then on) and
  `notifications/initialized`.

A request is first made the new way; a server that refuses its shape or version is given the handshake and asked
again, and what worked is remembered while the keyboard lives. A session the server lets go (404) is started again
once.

The AI side (`AiTools`) is the tool exchange for each of the four shapes `AiClient` speaks: Anthropic's Messages
API, OpenAI's chat completions (and the servers that speak them), OpenAI's Responses API, and Gemini.

## Checked

- `McpClientTest` (13 tests) against made-up servers of both generations: headers, the handshake, sessions, an event
  stream with the server's notes before the answer, paging, a 401 and a 403, a failing tool, unreadable answers.
- **Four real, public, sign-in-free servers, on 2026-11-12**, with a one-off probe that ran `McpClient.kt` as it is
  over a plain connection (not through `Net.kt`): their tools were listed, and one read-only tool called on three of
  them.

  | Server | How it answered | Tools | A call |
  | --- | --- | --- | --- |
  | learn.microsoft.com/api/mcp | the new way, no handshake; event stream | 3, all marked read-only | `microsoft_docs_search`: answered |
  | mcp.deepwiki.com/mcp | the older way, no session; event stream | 3, none marked | `read_wiki_structure`: answered |
  | huggingface.co/mcp | the older way, with a session; JSON | 4, all marked read-only | `hub_repo_search`: answered |
  | mcp.context7.com/mcp | the older way, no session; event stream | 2, both marked read-only | not called |

- `AiToolsTest` (12 tests): for each of the four shapes, that the model's own turn goes back as it came, each call
  is answered under its own id, a failing tool is told as a failure, and the cut-down schema Gemini is given.
- `ConnectorsTest` (12 tests): what may be an address, which tools start on, the AI's names for them, the limit,
  the list written and read back, and the guard's rule for the two paths.
- `test/connectors_settings_test.dart`: the pages, the form, the switches and the words.

## Not checked

- **No AI provider has been asked with tools.** The four exchanges are written from the providers' documentation
  and tested against answers made up to match it. Whether Claude, OpenAI, Gemini or the others accept these requests
  as written is untried; the fall-back above is there for that.
- The plugin has not been used on a device: no connector was added through Settings, and no `<<request>>` used one.
- The app's own connection to a connector (`Connectors.transport`, through `Net.kt`) was not run; the probe used a
  connection of its own.
- The specification was read through a summary on 2026-11-12, not page by page.

## Not built

- **Signing in through a browser (OAuth).** The specification's way: on a 401, read where the server's sign-in is
  (`WWW-Authenticate`, `/.well-known/oauth-protected-resource`), find the authorization server, register the app
  with it (a Client ID Metadata Document where it takes one, else Dynamic Client Registration), sign in in the
  browser with PKCE, and keep the tokens per server. `GhostOnline.kt` already does most of this for Ghost.md's own
  server. One thing needs a decision: the 2026-07-28 text says "All redirect URIs MUST be either localhost or use
  HTTPS", so the `ghostkbd://` return address Ghost.md's sign-in uses is not within it. The choices are a loopback
  address on the phone for the browser to come back to, or an https address on ghostkbd.com that opens the app.
- A confirmation before a tool that changes things is called. Until there is one, such tools stay off unless the
  person turns them on.
- Connectors in anything but Ghostwriter (Rewrite, the Prompt enhancer and Tidy do not use them).
- Resources and prompts, which MCP servers can also offer.
