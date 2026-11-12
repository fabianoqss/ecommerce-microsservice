import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
TAG = '0123456789abcdef0123456789abcdef01234567'
OTHER_TAG = 'abcdef0123456789abcdef0123456789abcdef01'
SECRET_NAMES = (
    'KEYCLOAK_ADMIN_PASSWORD', 'KEYCLOAK_DB_PASSWORD', 'KC_BFF_CLIENT_SECRET',
    'KC_ORDER_SERVICE_CLIENT_SECRET', 'KC_SMTP_USER', 'KC_SMTP_PASSWORD',
    'ORDER_DB_PASSWORD', 'INVENTORY_DB_PASSWORD', 'RABBITMQ_ADMIN_PASSWORD',
    'RABBITMQ_ORDER_PASSWORD', 'RABBITMQ_NOTIFICATION_PASSWORD',
    'RABBITMQ_INVENTORY_PASSWORD', 'REDIS_PASSWORD', 'GRAFANA_ADMIN_PASSWORD',
    'PRODUCT_MONGODB_PASSWORD',
)


class ReadImageTagTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        prod_dir = self.root / 'scripts/prod'
        prod_dir.mkdir(parents=True)
        script = prod_dir / 'generate-prod-env.sh'
        shutil.copy(ROOT / 'scripts/prod/generate-prod-env.sh', script)
        self.functions = script.read_text().rsplit('\nmain "$@"', 1)[0]
        self.env_file = self.root / '.env'

    def read_tag(self, tag=None):
        env = {'PATH': os.defpath}
        if tag is not None:
            env['IMAGE_TAG'] = tag
        return subprocess.run(['bash', '-c', self.functions + '\nread_image_tag "$1"',
                               'bash', str(self.env_file)], env=env, text=True, capture_output=True)

    def test_reads_only_tag_and_accepts_quotes_without_executing_env(self):
        marker = self.root / 'marcador'
        for quote in ('', "'", '"'):
            with self.subTest(aspas=quote):
                self.env_file.write_text(f'IGNORADO=$(touch {marker})\nIMAGE_TAG={quote}{TAG}{quote}')
                result = self.read_tag()
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(TAG + '\n', result.stdout)
                self.assertFalse(marker.exists())

    def test_environment_takes_precedence_and_empty_uses_file(self):
        self.env_file.write_text(f'IMAGE_TAG={TAG}\n')
        for value, expected in ((OTHER_TAG, OTHER_TAG), (f"'{OTHER_TAG}'", OTHER_TAG),
                                (f'"{OTHER_TAG}"', OTHER_TAG), ('', TAG)):
            with self.subTest(ambiente=value):
                result = self.read_tag(value)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected + '\n', result.stdout)

    def test_rejects_invalid_tag_without_printing_value(self):
        invalid_tags = ('abc', TAG.upper(), TAG[:-1], TAG + '0', 'g' * 40,
                        ' ' + TAG, TAG + ' ', TAG + '\r', TAG + '\n',
                        '\n' + TAG, f"'{TAG}\"", f'"{TAG}\n"')
        for value in invalid_tags:
            with self.subTest(valor=repr(value)):
                self.env_file.write_text(f'IMAGE_TAG={TAG}\n')
                result = self.read_tag(value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('IMAGE_TAG', result.stderr)
                self.assertEqual('', result.stdout)
                self.assertNotIn(value, result.stderr)

    def test_rejects_invalid_file_preserving_contents(self):
        for value in ('', TAG.upper(), TAG + '\r', f"'{TAG}\n'", f' {TAG}'):
            with self.subTest(valor=repr(value)):
                original = f'IMAGE_TAG={value}\n'.encode()
                self.env_file.write_bytes(original)
                result = self.read_tag()
                self.assertNotEqual(0, result.returncode)
                self.assertIn('IMAGE_TAG', result.stderr)
                self.assertEqual(original, self.env_file.read_bytes())

    def test_missing_tag_provides_first_generation_guidance(self):
        result = self.read_tag()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('IMAGE_TAG=<sha>', result.stderr)
        self.assertIn('first generation', result.stderr)
        self.assertFalse(self.env_file.exists())


@unittest.skipUnless(os.geteuid() == 0, 'Production generation requires root, without an EUID bypass.')
class GenerateProdEnvTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        prod_dir = self.root / 'scripts/prod'
        prod_dir.mkdir(parents=True)
        self.script = prod_dir / 'generate-prod-env.sh'
        shutil.copy(ROOT / 'scripts/prod/generate-prod-env.sh', self.script)
        (prod_dir / 'prod.env').write_text(
            'IMAGE_REGISTRY=registry.example.com\n'
            'PRODUCT_MONGODB_HOST=mongo.example.com\n'
            'PRODUCT_MONGODB_USERNAME=ecommerce\n'
        )
        self.env_file = self.root / '.env'
        self.bin_dir = self.root / 'bin'
        self.bin_dir.mkdir()
        for command in ('bash', 'dirname', 'date', 'cat', 'mktemp', 'chmod',
                        'chown', 'rm', 'cp', 'mv', 'touch'):
            (self.bin_dir / command).symlink_to(shutil.which(command))
        self.oci_calls = self.root / 'oci-calls'
        self.oci = self.bin_dir / 'oci-secret'
        self.oci.write_text(
            '#!/bin/sh\n'
            'printf "%s\\n" "$1" >> "$TEST_OCI_CALLS"\n'
            'printf "segredo-%s" "$1"\n'
        )
        self.oci.chmod(0o700)
        self.compose_env = self.root / 'compose-env'
        self.docker = self.bin_dir / 'docker'
        self.docker.write_text(f'#!{sys.executable}\n' + r'''
import os
from pathlib import Path
import re
import stat
import sys

assert sys.argv[1:] == [
    'compose', '--project-directory', os.environ['TEST_ROOT'],
    '--env-file', sys.argv[5], 'config', '--quiet',
]
env_file = Path(sys.argv[5])
metadata = env_file.stat()
assert stat.S_IMODE(metadata.st_mode) == 0o600
assert (metadata.st_uid, metadata.st_gid) == (0, 0)
content = env_file.read_text()
assert os.environ['IMAGE_TAG'] == os.environ['TEST_EXPECTED_TAG']
assert re.findall(r'^IMAGE_TAG=(.*)$', content, re.MULTILINE) == [os.environ['TEST_EXPECTED_TAG']]
Path(os.environ['TEST_COMPOSE_ENV']).write_text(content)
if os.environ.get('TEST_COMPOSE_FAIL') == '1':
    print('segredo-PRODUCT_MONGODB_PASSWORD')
    print('segredo-KEYCLOAK_ADMIN_PASSWORD', file=sys.stderr)
    sys.exit(1)
''')
        self.docker.chmod(0o700)

    def run_script(self, tag=None, expected_tag=TAG, compose_fail=False):
        env = {
            'PATH': str(self.bin_dir), 'VAULT_ID': 'vault-teste',
            'OCI_SECRET_CMD': str(self.oci), 'TEST_ROOT': str(self.root),
            'TEST_OCI_CALLS': str(self.oci_calls),
            'TEST_COMPOSE_ENV': str(self.compose_env),
            'TEST_EXPECTED_TAG': expected_tag,
            'TEST_COMPOSE_FAIL': '1' if compose_fail else '0',
        }
        if tag is not None:
            env['IMAGE_TAG'] = tag
        return subprocess.run([str(self.bin_dir / 'bash'), str(self.script)],
                              text=True, capture_output=True, env=env, cwd=self.root)

    def assert_tag(self, expected):
        content = self.env_file.read_text()
        self.assertEqual([f'IMAGE_TAG={expected}'],
                         [line for line in content.splitlines() if line.startswith('IMAGE_TAG=')])
        self.assertLess(content.index(f'IMAGE_TAG={expected}\n'), content.index('# OCI Vault secrets'))
        self.assertEqual(content, self.compose_env.read_text())

    def assert_private(self, path):
        metadata = path.stat()
        self.assertEqual(0o600, stat.S_IMODE(metadata.st_mode))
        self.assertEqual((0, 0), (metadata.st_uid, metadata.st_gid))

    def assert_no_secrets(self, result):
        for name in SECRET_NAMES:
            self.assertNotIn(f'segredo-{name}', result.stdout + result.stderr)

    def assert_rejected(self, result, original):
        self.assertNotEqual(0, result.returncode)
        self.assertIn('IMAGE_TAG', result.stderr)
        self.assertEqual(original, self.env_file.read_bytes())
        self.assertEqual([], list(self.root.glob('.env.tmp-*')))
        self.assertEqual([], list(self.root.glob('.env.bak-*')))
        self.assertFalse(self.oci_calls.exists())
        self.assertFalse(self.compose_env.exists())

    def test_preserves_existing_tag_configuration_secrets_and_private_backup(self):
        original = f'IMAGE_TAG={TAG}\nANTIGO=preservar-no-backup\n'
        self.env_file.write_text(original)
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_tag(TAG)
        self.assert_private(self.env_file)
        backups = list(self.root.glob('.env.bak-*'))
        self.assertEqual(1, len(backups))
        self.assertEqual(original, backups[0].read_text())
        self.assert_private(backups[0])
        content = self.env_file.read_text()
        self.assertIn('IMAGE_REGISTRY=registry.example.com\n', content)
        for name in SECRET_NAMES:
            self.assertIn(f"{name}='segredo-{name}'\n", content)
        self.assert_no_secrets(result)

    def test_environment_variable_takes_precedence(self):
        self.env_file.write_text('IMAGE_TAG=invalida\n')
        result = self.run_script(tag=OTHER_TAG, expected_tag=OTHER_TAG)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_tag(OTHER_TAG)

    def test_first_generation_uses_tag_from_environment(self):
        result = self.run_script(tag=TAG)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_tag(TAG)
        self.assertEqual([], list(self.root.glob('.env.bak-*')))

    def test_empty_environment_uses_existing_tag_without_trailing_newline(self):
        self.env_file.write_text(f'IMAGE_TAG={TAG}')
        result = self.run_script(tag='')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_tag(TAG)

    def test_accepts_matching_quotes_from_both_sources(self):
        for source in ('arquivo', 'ambiente'):
            for quote in ("'", '"'):
                with self.subTest(origem=source, aspas=quote):
                    self.env_file.write_text(f'IMAGE_TAG={quote}{TAG}{quote}\n')
                    result = self.run_script(tag=f'{quote}{TAG}{quote}' if source == 'ambiente' else None)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assert_tag(TAG)

    def test_refuses_invalid_tags_without_changing_env_or_querying_vault(self):
        invalid_tags = (
            'abc', TAG.upper(), TAG[:-1], TAG + '0', 'g' * 40,
            ' ' + TAG, TAG + ' ', '\t' + TAG, TAG + '\r',
            f"'{TAG}\"", f'"{TAG}', f"'{TAG} '",
            f'"{TAG}\n"', f'"{TAG}\r"',
        )
        for source in ('arquivo', 'ambiente'):
            for invalid in invalid_tags:
                with self.subTest(origem=source, valor=repr(invalid)):
                    original = f'IMAGE_TAG={invalid if source == "arquivo" else TAG}\n'.encode()
                    self.env_file.write_bytes(original)
                    result = self.run_script(tag=invalid if source == 'ambiente' else None)
                    self.assert_rejected(result, original)

    def test_refuses_line_breaks_in_environment(self):
        original = f'IMAGE_TAG={TAG}\n'.encode()
        self.env_file.write_bytes(original)
        for invalid in (TAG + '\n', '\n' + TAG, TAG + '\nOUTRO=valor', TAG + '\r\n'):
            with self.subTest(valor=repr(invalid)):
                self.assert_rejected(self.run_script(tag=invalid), original)

    def test_missing_tag_everywhere_provides_first_generation_guidance_without_creating_files(self):
        for tag in (None, ''):
            with self.subTest(ambiente=tag):
                result = self.run_script(tag=tag)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('IMAGE_TAG=<sha>', result.stderr)
                self.assertIn('environment', result.stderr)
                self.assertIn('first generation', result.stderr)
                self.assertFalse(self.env_file.exists())
                self.assertFalse(self.oci_calls.exists())
                self.assertEqual([], list(self.root.glob('.env.*')))

    def test_existing_file_without_tag_remains_intact(self):
        original = b'OUTRO=valor\n'
        self.env_file.write_bytes(original)
        self.assert_rejected(self.run_script(), original)

    def test_does_not_execute_existing_env_contents(self):
        marker = self.root / 'marcador'
        self.env_file.write_text(f'IGNORADO=$(touch {marker})\nIMAGE_TAG={TAG}\n')
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(marker.exists())
        self.assert_tag(TAG)
        self.assert_no_secrets(result)

    def test_compose_failure_preserves_env_without_exposing_secrets(self):
        original = f'IMAGE_TAG={TAG}\n'
        self.env_file.write_text(original)
        result = self.run_script(compose_fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Docker Compose', result.stderr)
        self.assertEqual(original, self.env_file.read_text())
        self.assertTrue(self.compose_env.exists())
        self.assertEqual([], list(self.root.glob('.env.tmp-*')))
        self.assertEqual([], list(self.root.glob('.env.bak-*')))
        self.assert_no_secrets(result)

    def test_generates_env_when_docker_is_not_on_path(self):
        self.docker.unlink()
        result = self.run_script(tag=TAG)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f'IMAGE_TAG={TAG}\n', self.env_file.read_text())
        self.assertFalse(self.compose_env.exists())
        self.assert_no_secrets(result)

    def test_refuses_symbolic_link_without_changing_target(self):
        target = self.root / 'destino'
        original = f'IMAGE_TAG={TAG}\n'
        target.write_text(original)
        self.env_file.symlink_to(target)
        result = self.run_script(tag=TAG)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('symbolic link', result.stderr)
        self.assertTrue(self.env_file.is_symlink())
        self.assertEqual(original, target.read_text())
        self.assertFalse(self.oci_calls.exists())


if __name__ == '__main__':
    unittest.main()
