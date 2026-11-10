#!/usr/bin/env ruby
# Brings Runner.xcodeproj in line with the folders: the GhostKeyboard extension target, and the Swift sources the app
# and the extension share (GhostCore, Shared). Safe to run again after adding or removing a Swift file:
#
#   GEM_HOME=$(brew --prefix cocoapods)/libexec ruby ios/tool/sync_xcodeproj.rb
#
# (the xcodeproj gem comes with CocoaPods).
require 'xcodeproj'

ROOT = File.expand_path('..', __dir__)
TEAM = 'F6ZAL7ANAD'
APP_ID = 'com.infamousvague.ghostkbd'
EXT_ID = "#{APP_ID}.keyboard"

project = Xcodeproj::Project.open(File.join(ROOT, 'Runner.xcodeproj'))
runner = project.targets.find { |t| t.name == 'Runner' } or abort 'no Runner target'

def group_for(project, name, path)
  group = project.main_group.children.find { |c| c.display_name == name && c.is_a?(Xcodeproj::Project::Object::PBXGroup) }
  group ||= project.main_group.new_group(name, path)
  group.recursive_children.select { |c| c.is_a?(Xcodeproj::Project::Object::PBXFileReference) }.each(&:remove_from_project)
  group.clear
  group
end

def refs(group, dir, pattern)
  Dir.glob(File.join(dir, pattern)).sort.map { |f| group.new_reference(File.basename(f)) }
end

keyboard_group = group_for(project, 'GhostKeyboard', 'GhostKeyboard')
core_group = group_for(project, 'GhostCore', 'GhostCore/Sources/GhostCore')
shared_group = group_for(project, 'Shared', 'Shared')

keyboard_sources = refs(keyboard_group, File.join(ROOT, 'GhostKeyboard'), '*.swift')
core_sources = refs(core_group, File.join(ROOT, 'GhostCore/Sources/GhostCore'), '*.swift')
shared_sources = refs(shared_group, File.join(ROOT, 'Shared'), '*.swift')
keyboard_plist = keyboard_group.new_reference('Info.plist')
keyboard_group.new_reference('GhostKeyboard.entitlements')
keyboard_privacy = keyboard_group.new_reference('PrivacyInfo.xcprivacy')

# The dictionaries are Android's own files, copied into the extension as a folder: one copy in the repo.
dict = keyboard_group.new_reference('../../android/app/src/main/assets/dict')
dict.last_known_file_type = 'folder'
dict.name = 'dict'

# The app's own extra files (the channel, its entitlements, its privacy manifest) live in Runner's group.
runner_group = project.main_group.children.find { |c| c.display_name == 'Runner' }
def ensure_ref(group, name)
  group.children.find { |c| c.display_name == name } || group.new_reference(name)
end
channel_refs = Dir.glob(File.join(ROOT, 'Runner', '*.swift')).map { |f| File.basename(f) }.reject { |n| %w[AppDelegate.swift SceneDelegate.swift].include?(n) }.sort.map { |n| ensure_ref(runner_group, n) }
ensure_ref(runner_group, 'Runner.entitlements')
runner_privacy = ensure_ref(runner_group, 'PrivacyInfo.xcprivacy')

ext = project.targets.find { |t| t.name == 'GhostKeyboard' }
unless ext
  ext = project.new_target(:app_extension, 'GhostKeyboard', :ios, '15.0', nil, :swift)
  # new_target makes Debug and Release; Flutter has a Profile configuration too.
  unless ext.build_configurations.any? { |c| c.name == 'Profile' }
    release = ext.build_configurations.find { |c| c.name == 'Release' }
    profile = project.new(Xcodeproj::Project::Object::XCBuildConfiguration)
    profile.name = 'Profile'
    profile.build_settings = release.build_settings.dup
    ext.build_configuration_list.build_configurations << profile
  end
end

flutter_group = project.main_group.children.find { |c| c.display_name == 'Flutter' }
xcconfig = { 'Debug' => 'Debug.xcconfig', 'Release' => 'Release.xcconfig', 'Profile' => 'Release.xcconfig' }
ext.build_configurations.each do |config|
  config.base_configuration_reference = flutter_group.children.find { |c| c.display_name == xcconfig[config.name] }
  s = config.build_settings
  s['PRODUCT_BUNDLE_IDENTIFIER'] = EXT_ID
  s['PRODUCT_NAME'] = '$(TARGET_NAME)'
  s['INFOPLIST_FILE'] = 'GhostKeyboard/Info.plist'
  s['CODE_SIGN_ENTITLEMENTS'] = 'GhostKeyboard/GhostKeyboard.entitlements'
  s['DEVELOPMENT_TEAM'] = TEAM
  s['CODE_SIGN_STYLE'] = 'Automatic'
  s['IPHONEOS_DEPLOYMENT_TARGET'] = '15.0'
  s['SWIFT_VERSION'] = '5.0'
  s['TARGETED_DEVICE_FAMILY'] = '1,2'
  s['APPLICATION_EXTENSION_API_ONLY'] = 'YES'
  s['SKIP_INSTALL'] = 'YES'
  s['MARKETING_VERSION'] = '$(FLUTTER_BUILD_NAME)'
  s['CURRENT_PROJECT_VERSION'] = '$(FLUTTER_BUILD_NUMBER)'
  s['GENERATE_INFOPLIST_FILE'] = 'NO'
  s['LD_RUNPATH_SEARCH_PATHS'] = ['$(inherited)', '@executable_path/Frameworks', '@executable_path/../../Frameworks']
  s['SWIFT_OPTIMIZATION_LEVEL'] = config.name == 'Debug' ? '-Onone' : '-O'
  s['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] = config.name == 'Debug' ? '$(inherited) DEBUG GHOST_KEYBOARD' : '$(inherited) GHOST_KEYBOARD'
  s['ENABLE_USER_SCRIPT_SANDBOXING'] = 'NO'
  s.delete('ASSETCATALOG_COMPILER_APPICON_NAME')
end

ext.source_build_phase.clear
(keyboard_sources + core_sources + shared_sources).each { |r| ext.source_build_phase.add_file_reference(r) }
ext.resources_build_phase.clear
ext.resources_build_phase.add_file_reference(dict)
ext.resources_build_phase.add_file_reference(keyboard_privacy)

# The app: the shared sources and the channel beside Flutter's own files.
ours = (core_sources + shared_sources + channel_refs)
runner.source_build_phase.files.select { |f| f.file_ref.nil? }.each(&:remove_from_project)
have = runner.source_build_phase.files.map(&:file_ref)
ours.each { |r| runner.source_build_phase.add_file_reference(r) unless have.include?(r) }
runner.resources_build_phase.add_file_reference(runner_privacy) unless runner.resources_build_phase.files.map(&:file_ref).include?(runner_privacy)

runner.build_configurations.each do |config|
  s = config.build_settings
  s['PRODUCT_BUNDLE_IDENTIFIER'] = APP_ID
  s['CODE_SIGN_ENTITLEMENTS'] = 'Runner/Runner.entitlements'
  s['DEVELOPMENT_TEAM'] = TEAM
  s['TARGETED_DEVICE_FAMILY'] = '1,2'
  s['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] = '$(inherited) DEBUG' if config.name == 'Debug'
end
project.targets.select { |t| t.name == 'RunnerTests' }.each do |t|
  t.build_configurations.each { |c| c.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = "#{APP_ID}.RunnerTests" }
end

runner.add_dependency(ext) unless runner.dependencies.any? { |d| d.target == ext }
embed = runner.copy_files_build_phases.find { |p| p.name == 'Embed Foundation Extensions' }
unless embed
  embed = runner.new_copy_files_build_phase('Embed Foundation Extensions')
  embed.dst_subfolder_spec = '13'
  # Before Flutter's "Thin Binary" script, or Xcode reports a build cycle.
  thin = runner.build_phases.find { |p| p.respond_to?(:name) && p.name == 'Thin Binary' }
  runner.build_phases.move(embed, runner.build_phases.index(thin)) if thin
end
unless embed.files.any? { |f| f.file_ref == ext.product_reference }
  file = embed.add_file_reference(ext.product_reference)
  file.settings = { 'ATTRIBUTES' => ['RemoveHeadersOnCopy'] }
end

# UI tests that drive the keyboard on the Simulator (ios/tool/ui_test.sh).
ui_group = group_for(project, 'RunnerUITests', 'RunnerUITests')
ui_sources = refs(ui_group, File.join(ROOT, 'RunnerUITests'), '*.swift')
ui = project.targets.find { |t| t.name == 'RunnerUITests' }
unless ui
  ui = project.new_target(:ui_test_bundle, 'RunnerUITests', :ios, '15.0', nil, :swift)
  ui.add_dependency(runner)
end
ui.build_configurations.each do |config|
  s = config.build_settings
  s['PRODUCT_BUNDLE_IDENTIFIER'] = "#{APP_ID}.RunnerUITests"
  s['TEST_TARGET_NAME'] = 'Runner'
  s['GENERATE_INFOPLIST_FILE'] = 'YES'
  s['DEVELOPMENT_TEAM'] = TEAM
  s['SWIFT_VERSION'] = '5.0'
  s['IPHONEOS_DEPLOYMENT_TARGET'] = '15.0'
  s['PRODUCT_NAME'] = '$(TARGET_NAME)'
end
unless ui.build_configurations.any? { |c| c.name == 'Profile' }
  profile = project.new(Xcodeproj::Project::Object::XCBuildConfiguration)
  profile.name = 'Profile'
  profile.build_settings = ui.build_configurations.find { |c| c.name == 'Release' }.build_settings.dup
  ui.build_configuration_list.build_configurations << profile
end
ui.source_build_phase.clear
ui_sources.each { |r| ui.source_build_phase.add_file_reference(r) }
# The tests place their taps with the keyboard's own layout.
core_sources.select { |r| %w[Layouts.swift].include?(r.display_name) }.each { |r| ui.source_build_phase.add_file_reference(r) }

project.save

# A scheme for the UI tests alone, so `xcodebuild test -scheme RunnerUITests` runs just them.
scheme_path = File.join(ROOT, 'Runner.xcodeproj', 'xcshareddata', 'xcschemes', 'RunnerUITests.xcscheme')
unless File.exist?(scheme_path)
  scheme = Xcodeproj::XCScheme.new
  scheme.add_build_target(runner)
  scheme.add_test_target(ui)
  scheme.save_as(File.join(ROOT, 'Runner.xcodeproj'), 'RunnerUITests', true)
end
puts "Runner: #{runner.source_build_phase.files.size} sources; GhostKeyboard: #{ext.source_build_phase.files.size} sources"
