// Settings › Connectors (android Connectors.kt, McpClient.kt, AiTools.kt; docs/connectors.md): a Market plugin with a
// page of its own, a page for each connector with a switch for each of its tools, and Ghostwriter's own switch for
// them. What is said on these pages is a promise about who is sent what, so the words are held. Run with `flutter test`.

import 'package:dev_keyboard/plugins/catalogue.dart';
import 'package:dev_keyboard/settings/kit/settings_kit.dart';
import 'package:dev_keyboard/settings/panes.dart';
import 'package:dev_keyboard/settings/settings_css.dart';
import 'package:dev_keyboard/settings/settings_screen.dart';
import 'package:dev_keyboard/settings_store.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:glacier/glacier.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // The phone's side of the channel: the connectors it keeps, answered to every connector call.
  final calls = <MethodCall>[];
  Map<String, Object?>? phone;
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(const MethodChannel('dev_keyboard/settings'), (call) async {
    calls.add(call);
    return call.method.startsWith('connector') ? phone : null;
  });

  Map<String, Object?> calendar({bool listOn = true, bool createOn = false, String? error, int checkedAt = 1731400000000}) => {
        'id': 'a1',
        'name': 'Calendar',
        'url': 'https://cal.example.com/mcp',
        'host': 'cal.example.com',
        'hasToken': true,
        'checkedAt': checkedAt,
        'error': error,
        'tools': [
          {'name': 'list_events', 'title': 'List events', 'description': 'Events between two dates.', 'readOnly': true, 'on': listOn},
          {'name': 'create_event', 'title': '', 'description': 'Adds an event.', 'readOnly': false, 'on': createOn},
          {'name': 'search', 'title': '', 'description': '', 'readOnly': null, 'on': false},
        ],
      };

  testWidgets('Connectors has a page of its own, a page for each connector, and says who is sent what', (tester) async {
    final sheet = await tester.runAsync(() => loadTokenSheet(more: const ['assets/css/ink.css', 'assets/css/app.css']));
    installSettingsStyles(sheet!);
    final store = SettingsStore();
    late BuildContext context;
    await tester.pumpWidget(Directionality(
      textDirection: TextDirection.ltr,
      child: GlacierTheme(sheet: sheet, attrs: const {'platform': 'mobile', 'theme': 'dark'}, child: Builder(builder: (c) {
        context = c;
        return const SizedBox();
      })),
    ));
    SettingsSection? section() => settingsSections(store).where((s) => s.id == 'connectors').firstOrNull;
    List<PaneSection> sections() => section()!.page(context, store).whereType<PaneSection>().toList();
    List<SettingRow> rows() => sections().expand((s) => s.children.whereType<SettingRow>()).toList();
    SettingRow row(String label) => rows().firstWhere((r) => r.label == label);

    // Not added: no page, and Ghostwriter's sources say nothing of connectors.
    expect(section(), isNull);
    await store.setPlugin('ghostwriter', true);
    List<SettingRow> sources() => settingsSections(store).firstWhere((s) => s.id == 'ghostwriter').parts!(store).firstWhere((p) => p.id == 'ai-sources').page(context, store).whereType<PaneSection>().first.children.whereType<SettingRow>().toList();
    expect(sources().any((r) => r.label == 'My connectors'), isFalse);

    await store.setPlugin('connectors', true);
    expect(section()!.label, 'Connectors');
    expect(section()!.group, 'plugins');
    expect(section()!.summary!(store), 'Add an MCP server');
    expect(section()!.page(context, store).whereType<PaneHero>().single.title, 'No connectors yet');

    // The form: three fields, and under them what a token is and what cannot be added yet.
    final form = sections().firstWhere((s) => s.title == 'Add a connector');
    final fields = form.children.whereType<SettingRowBox>().map((b) => b.child as GlacierInput).toList();
    expect(fields.map((f) => f.ariaLabel), ['Connector name', 'Connector address', 'Connector token']);
    expect(fields[2].type, GlacierInputType.password);
    for (final phrase in ['starts with https://', 'to that server as its bearer token, and to nowhere else', 'signed in to through a browser cannot be added yet', 'Kept on this phone']) {
      expect((form.footer as Text).data, contains(phrase));
    }
    // Who talks to whom, in rows a person can read before adding anything.
    for (final label in ['Your phone asks the connector', 'What a tool answers goes to your AI', 'Keys and tokens stay apart', 'Only when you ask']) {
      expect(rows().map((r) => r.label), contains(label));
    }
    expect((row('Keys and tokens stay apart').hint as Text).data, allOf(contains('AI key never goes to a connector'), contains('token never goes to your AI')));

    // Ghostwriter has its switch for them now, on, and says nothing is switched on yet.
    final mine = sources().firstWhere((r) => r.label == 'My connectors');
    expect((mine.control as GlacierSwitch).checked, isTrue);
    expect((mine.hint as Text).data, contains('No tool is switched on yet'));

    // A refusal is the keyboard's own words, and the form stays as written.
    store.connectorName = 'Calendar';
    store.connectorUrl = 'http://cal.example.com/mcp';
    phone = {'connectors': [], 'on': 0, 'offered': 0, 'max': 8, 'maxOffered': 16, 'error': 'The address must start with https://'};
    await store.addConnector();
    expect(rows().last.label == 'The address must start with https://' || rows().any((r) => r.label == 'The address must start with https://'), isTrue);
    expect(store.connectorUrl, 'http://cal.example.com/mcp');
    expect(store.connectorForm, 0);

    // Added: the phone is sent the three fields trimmed, the form is emptied, and the connector has a page.
    store.connectorUrl = ' https://cal.example.com/mcp ';
    store.connectorToken = ' s3cr3t ';
    phone = {
      'connectors': [calendar()],
      'on': 1, 'offered': 1, 'max': 8, 'maxOffered': 16,
    };
    await store.addConnector();
    final added = calls.lastWhere((c) => c.method == 'connectorAdd').arguments as Map;
    expect(added, {'name': 'Calendar', 'url': 'https://cal.example.com/mcp', 'token': 's3cr3t'});
    expect(store.connectorName, '');
    expect(store.connectorToken, '');
    expect(store.connectorForm, 1);
    expect(section()!.summary!(store), '1 connector, 1 tool on');
    expect(row('Tools switched on').value, isA<Text>().having((t) => t.data, 'count', '1'));
    expect((sources().firstWhere((r) => r.label == 'My connectors').hint as Text).data, contains('Your AI may call the tools you switched on'));
    // The token is never among what Settings is told.
    expect('${store.connectorsState}', isNot(contains('s3cr3t')));

    // Its own page: the server, Refresh, and a switch for each tool saying what its server says of it.
    final part = section()!.parts!(store).single;
    expect(part.label, 'Calendar');
    expect(part.summary!(store), '3 tools, 1 on');
    List<SettingRow> own() => part.page(context, store).whereType<PaneSection>().expand((s) => s.children.whereType<SettingRow>()).toList();
    expect((own().firstWhere((r) => r.label == 'Server').value as Text).data, 'With a token');
    expect((own().firstWhere((r) => r.label == 'Server').hint as Text).data, 'https://cal.example.com/mcp');
    final list = own().firstWhere((r) => r.label == 'List events');
    expect((list.control as GlacierSwitch).checked, isTrue);
    expect((list.hint as Text).data, 'Events between two dates.\nIts server marks it read-only.');
    final create = own().firstWhere((r) => r.label == 'create_event');
    expect((create.control as GlacierSwitch).checked, isFalse);
    expect((create.hint as Text).data, contains('Its server says it can change things.'));
    expect((own().firstWhere((r) => r.label == 'search').hint as Text).data, 'Its server does not say whether it changes anything.');
    final tools = part.page(context, store).whereType<PaneSection>().firstWhere((s) => s.title == 'Its tools');
    for (final phrase in ['arguments it makes from your request', 'starts on; every other starts off', 'the server’s own word, not a promise']) {
      expect((tools.footer as Text).data, contains(phrase));
    }

    // Switching a tool on asks the phone for exactly that tool; Refresh and Remove ask for that connector.
    phone = {
      'connectors': [calendar(createOn: true)],
      'on': 2, 'offered': 2, 'max': 8, 'maxOffered': 16,
    };
    (create.control as GlacierSwitch).onCheckedChange!(true);
    await tester.pump();
    expect(calls.lastWhere((c) => c.method == 'connectorTool').arguments, {'id': 'a1', 'tool': 'create_event', 'on': true});
    expect(part.summary!(store), '3 tools, 2 on');
    own().firstWhere((r) => r.label == 'Refresh its tools').onPress!();
    await tester.pump();
    expect(calls.lastWhere((c) => c.method == 'connectorRefresh').arguments, {'id': 'a1'});

    // A server that could not be read says why, on the list and on its page; trouble in a request is shown too.
    phone = {
      'connectors': [calendar(error: 'This server wants a sign-in.')],
      'on': 1, 'offered': 1, 'max': 8, 'maxOffered': 16,
      'note': '2026-11-12: your AI answered without the connectors. The AI said no (400)',
    };
    await store.refreshConnectors();
    expect(part.summary!(store), 'This server wants a sign-in.');
    expect(own().any((r) => r.label == 'It could not be read'), isTrue);
    expect((row('Last trouble').hint as Text).data, contains('answered without the connectors'));

    // More tools on than the AI is given: the page says both numbers.
    phone = {
      'connectors': [calendar()],
      'on': 40, 'offered': 16, 'max': 8, 'maxOffered': 16,
    };
    await store.refreshConnectors();
    expect((sections().firstWhere((s) => s.children.whereType<SettingRow>().any((r) => r.label == 'Tools switched on')).footer as Text).data, contains('40 tools are on, and your AI is given 16 of them'));

    // Offline: the page says so, and the two rows that would ask a server say why they cannot.
    await store.set('offline', true);
    expect(section()!.summary!(store), 'Offline is on');
    expect(row('Add it and read its tools').disabledReason, 'Offline is on');
  });

  test('the Market offers it as an add-on that reaches only the servers the person adds, and not on iPhone', () {
    final plugin = pluginById('connectors')!;
    expect(plugin.name, 'Connectors');
    expect(marketCatalogue.any((p) => p.id == 'connectors'), isTrue);
    expect(plugin.hasPage, isTrue);
    expect(plugin.ios, isFalse);
    expect(plugin.hosts, ['the servers you add']);
    for (final phrase in [
      'Model Context Protocol',
      'the ones the server marks read-only start on, every other starts off',
      'token goes to that server and nowhere else',
      'your AI key never goes to a connector',
      'What the tools answer is sent to your AI',
      'signed in to through a browser cannot be added yet',
    ]) {
      expect(plugin.description, contains(phrase));
    }
  });
}
