// Compile the production classifier with a real XML parser, without Gradle or Android mocks.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const cache = process.argv[2] || path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1');
function jar(group, name, version) {
    const directory = path.join(cache, group, name, version);
    for (const hash of fs.readdirSync(directory)) {
        const candidate = path.join(directory, hash, name + '-' + version + '.jar');
        if (fs.existsSync(candidate)) return candidate;
    }
    throw new Error('Missing dependency: ' + directory);
}
const stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0');
const xml = jar('net.sf.kxml', 'kxml2', '2.3.0');
const compiler = [
    jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0'), stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.3.20'),
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.0'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    jar('org.jetbrains', 'annotations', '13.0')
];
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-message-type-tests-'));
const artifact = path.join(output, 'labels.jar');
const java = process.env.JAVA || 'java';
function run(args) {
    const result = spawnSync(java, args, {cwd: root, encoding: 'utf8', timeout: 60000});
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
run(['-Xmx256m', '-cp', compiler.join(path.delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', [stdlib, xml].join(path.delimiter), '-d', artifact,
    'app/src/main/java/h/Hchat/hooks/api/model/WeChatMessageTypes.kt',
    'app/src/main/java/h/Hchat/hooks/items/hchatextra/MessageTypeLabels.kt',
    'scripts/tests/message_type_labels/MessageTypeLabelsRegression.kt']);
run(['-cp', [artifact, stdlib, xml].join(path.delimiter), 'h.Hchat.hooks.items.hchatextra.MessageTypeLabelsRegressionKt']);
console.log('Test artifacts: ' + output);
