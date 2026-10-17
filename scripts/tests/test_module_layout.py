"""Static layout and ABI-discovery checks; no Gradle, JDK, or external dependencies."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from klib_layout import module_directories, production_class_directories

SPEC = importlib.util.spec_from_file_location('klib_abi', SCRIPTS / 'check-jvm-abi.py')
ABI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ABI)


class ModuleLayoutTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def put(self, relative, text=''):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
        return path

    def test_old_flat_baseline_discovers_only_main_java_and_kotlin_outputs(self):
        java = self.put('klib-core/build/classes/java/main/sample/Public.class')
        kotlin = self.put('klib-script/build/classes/kotlin/main/sample/Kotlin.class')
        for source_set in ('test', 'testFixtures', 'integrationTest'):
            self.put(f'klib-core/build/classes/java/{source_set}/sample/Probe.class')
            self.put(f'klib-script/build/classes/kotlin/{source_set}/sample/Probe.class')
        self.put('build/classes/java/main/sample/Root.class')
        with patch.object(ABI, 'read_class', side_effect=lambda p: (p.stem, {'flags': 1})) as read:
            self.assertEqual({'Public', 'Kotlin'}, set(ABI.snapshot(self.root)))
            self.assertEqual({java, kotlin}, {call.args[0] for call in read.call_args_list})

    def test_grouped_manifest_is_authoritative_and_keeps_test_named_production_packages(self):
        self.put('gradle/module-layout.properties',
                 '# identities stay stable\nklib-core=libraries/core\n'
                 'klib-compat-v26=platform/versions/v26\n')
        main = self.put('libraries/core/build/classes/kotlin/main/test/Public.class')
        version = self.put('platform/versions/v26/build/classes/java/main/sample/Bridge.class')
        self.put('libraries/core/build/classes/kotlin/test/sample/Test.class')
        self.put('platform/versions/v26/build/classes/java/testFixtures/sample/Fixture.class')
        self.put('klib-core/build/classes/java/main/sample/Stale.class')
        self.assertEqual({'klib-core', 'klib-compat-v26'}, set(module_directories(self.root)))
        with patch.object(ABI, 'read_class', side_effect=lambda p: (p.stem, {'flags': 1})) as read:
            self.assertEqual({'Public', 'Bridge'}, set(ABI.snapshot(self.root)))
            self.assertEqual({main, version}, {call.args[0] for call in read.call_args_list})

    def test_snapshot_excludes_nonpublic_production_classes(self):
        self.put('klib-core/build/classes/java/main/sample/Private.class')
        with patch.object(ABI, 'read_class', return_value=('Private', {'flags': 0})):
            self.assertEqual({}, ABI.snapshot(self.root))

    def test_missing_outputs_cannot_write_an_empty_baseline(self):
        output = self.root / 'baseline.json'
        result = subprocess.run([sys.executable, str(SCRIPTS / 'check-jvm-abi.py'),
                                 str(self.root), '--save', str(output)],
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('No exported production classes', result.stderr)
        self.assertFalse(output.exists())

    def test_invalid_or_duplicate_manifest_entries_fail_closed(self):
        for text in ('', 'core=libraries/core\n', 'klib-core=../escape\n',
                     'klib-core=/absolute\n', 'klib-core=\n',
                     'klib-core=libraries/core\nklib-core=libraries/other\n',
                     'klib-core=libraries/core\nklib-item=libraries/core\n'):
            with self.subTest(manifest=text):
                self.put('gradle/module-layout.properties', text)
                with self.assertRaises(ValueError):
                    module_directories(self.root)

    def test_repository_manifest_preserves_all_published_identities(self):
        root = SCRIPTS.parent
        expected = {
            **{f'klib-{name}': f'libraries/{name}'
               for name in ('core', 'command', 'config', 'lang', 'item', 'ui', 'hook')},
            'klib-data': 'storage/data',
            **{f'klib-data-{name}': f'storage/{name}'
               for name in ('jdbc', 'json', 'sqlite', 'mysql', 'postgresql')},
            'klib-compat': 'platform/compat',
            **{f'klib-compat-{name}': f'platform/versions/{name}'
               for name in ('v1_12', 'v1_20', 'v1_21', 'v26')},
            **{f'klib-{name}': f'runtime/{name}' for name in ('script', 'remote', 'guard-api')},
        }
        modules = module_directories(root)
        self.assertEqual(expected, {name: path.relative_to(root).as_posix()
                                    for name, path in modules.items()})
        for name, path in modules.items():
            self.assertTrue((path / 'src/main').is_dir(), name)
            self.assertFalse((root / name).exists(), name)


if __name__ == '__main__':
    unittest.main()
