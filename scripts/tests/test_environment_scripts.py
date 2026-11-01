"""Contratos dos scripts de ambiente, sem executar arquivos .env como código."""
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class GenerateEnvTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'scripts').mkdir()
        shutil.copy(ROOT / 'scripts/generate-env.sh', self.root / 'scripts')
        self.template = (
            '# exemplo\nA=troque-primeiro\nB=troque-segundo\n'
            'POLITICA=troque-politica-usuario\nFIXO=localhost\nVAZIO=\nREF=${A}\n'
        )
        (self.root / '.env.example').write_text(self.template)

    def run_script(self, *args, env=None):
        return subprocess.run(['bash', str(self.root / 'scripts/generate-env.sh'), *args],
                              text=True, capture_output=True, env=env)

    def assert_policy_secret(self, value, output=''):
        self.assertEqual(24, len(value))
        self.assertRegex(value, r'^[A-Za-z0-9._-]{24}$')
        self.assertRegex(value, r'[A-Z]')
        self.assertRegex(value, r'[a-z]')
        self.assertRegex(value, r'[0-9]')
        self.assertRegex(value, r'[._-]')
        if output:
            self.assertNotIn(value, output)

    def test_substitui_todos_os_segredos_preserva_configuracao_e_protege_arquivo(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        values = dict(line.split('=', 1) for line in (self.root / '.env').read_text().splitlines() if '=' in line)
        for key in ('A', 'B'):
            self.assertRegex(values[key], r'^[0-9a-f]{48}$')
            self.assertNotIn(values[key], result.stdout + result.stderr)
        self.assert_policy_secret(values['POLITICA'], result.stdout + result.stderr)
        self.assertNotEqual(values['A'], values['B'])
        self.assertEqual('localhost', values['FIXO'])
        self.assertEqual('', values['VAZIO'])
        self.assertEqual('${A}', values['REF'])
        self.assertEqual(0o600, stat.S_IMODE((self.root / '.env').stat().st_mode))

    def test_env_example_gera_senha_de_usuario_com_politica_e_demais_segredos_em_hex(self):
        shutil.copy(ROOT / '.env.example', self.root / '.env.example')
        first_run = self.run_script()
        self.assertEqual(0, first_run.returncode, first_run.stderr)
        first_values = dict(
            line.split('=', 1) for line in (self.root / '.env').read_text().splitlines() if '=' in line
        )
        second_run = self.run_script('--force')
        self.assertEqual(0, second_run.returncode, second_run.stderr)
        second_values = dict(
            line.split('=', 1) for line in (self.root / '.env').read_text().splitlines() if '=' in line
        )
        combined_output = first_run.stdout + first_run.stderr + second_run.stdout + second_run.stderr
        self.assert_policy_secret(first_values['KC_ADMIN_USER_PASSWORD'], combined_output)
        self.assert_policy_secret(second_values['KC_ADMIN_USER_PASSWORD'], combined_output)
        self.assertNotEqual(first_values['KC_ADMIN_USER_PASSWORD'], second_values['KC_ADMIN_USER_PASSWORD'])
        hex_keys = (
            'DB_PASSWORD', 'KEYCLOAK_ADMIN_PASSWORD', 'KEYCLOAK_DB_PASSWORD',
            'KC_BFF_CLIENT_SECRET', 'KC_ORDER_SERVICE_CLIENT_SECRET',
            'RABBITMQ_ADMIN_PASSWORD', 'RABBITMQ_ORDER_PASSWORD',
            'RABBITMQ_NOTIFICATION_PASSWORD', 'RABBITMQ_INVENTORY_PASSWORD',
            'REDIS_PASSWORD', 'GRAFANA_ADMIN_PASSWORD',
        )
        for key in hex_keys:
            self.assertRegex(first_values[key], r'^[0-9a-f]{48}$')
            self.assertRegex(second_values[key], r'^[0-9a-f]{48}$')
            self.assertNotEqual(first_values[key], second_values[key])
            self.assertNotIn(first_values[key], combined_output)
            self.assertNotIn(second_values[key], combined_output)

    def test_caracteres_garantidos_da_politica_nao_ficam_em_posicao_fixa(self):
        (self.root / '.env.example').write_text(
            ''.join(f'U{i}=troque-politica-{i}\n' for i in range(12))
        )
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        values = [
            line.split('=', 1)[1]
            for line in (self.root / '.env').read_text().splitlines()
            if '=' in line
        ]
        for value in values:
            self.assert_policy_secret(value, result.stdout + result.stderr)
        special_positions = {
             next(i for i, ch in enumerate(value) if ch in '-_.')
            for value in values
        }
        self.assertGreater(len(special_positions), 1)

    def test_recusa_sobrescrita_e_force_preserva_backup_privado(self):
        original = self.root / '.env'
        original.write_text('ANTIGO=valor-local\n')
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('ANTIGO=valor-local\n', original.read_text())
        self.assertEqual([], list(self.root.glob('.env.bak-*')))
        result = self.run_script('--force')
        self.assertEqual(0, result.returncode, result.stderr)
        backups = list(self.root.glob('.env.bak-*'))
        self.assertEqual(1, len(backups))
        self.assertEqual('ANTIGO=valor-local\n', backups[0].read_text())
        self.assertEqual(0o600, stat.S_IMODE(backups[0].stat().st_mode))
        self.assertNotIn('ANTIGO=', original.read_text())

    def test_recusa_link_simbolico_mesmo_com_force(self):
        target = self.root / 'outro-arquivo'
        target.write_text('preservado')
        (self.root / '.env').symlink_to(target)
        result = self.run_script('--force')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('preservado', target.read_text())
        self.assertTrue((self.root / '.env').is_symlink())

    def test_falha_do_openssl_nao_cria_env_parcial(self):
        bin_dir = self.root / 'bin'
        bin_dir.mkdir()
        openssl = bin_dir / 'openssl'
        openssl.write_text('#!/bin/sh\nexit 1\n')
        openssl.chmod(0o700)
        for template in (self.template, 'POLITICA=troque-politica-usuario\n'):
            with self.subTest(template=template):
                (self.root / '.env.example').write_text(template)
                result = self.run_script(env={**os.environ, 'PATH': f'{bin_dir}:{os.environ["PATH"]}'})
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.root / '.env').exists())


class IdeEnvTest(unittest.TestCase):
    def test_emite_credenciais_por_servico_e_enderecos_locais_sem_executar_env(self):
        with tempfile.TemporaryDirectory() as tmp:
            env_file = Path(tmp) / '.env'
            values = {
                'DB_PASSWORD': 'db-local', 'KC_ORDER_SERVICE_CLIENT_SECRET': 'order-oidc',
                'KC_BFF_CLIENT_SECRET': 'bff-oidc', 'RABBITMQ_ORDER_PASSWORD': 'order-amqp',
                'RABBITMQ_NOTIFICATION_PASSWORD': 'notification-amqp', 'REDIS_PASSWORD': 'redis-local',
                'RABBITMQ_INVENTORY_PASSWORD': 'inventory-amqp',
                'TRACING_SAMPLING_PROBABILITY': '1.0', 'IGNORADO': '$(touch arquivo-nao-deve-existir)',
            }
            env_file.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
            result = subprocess.run(['bash', str(ROOT / 'scripts/ide-env.sh'), str(env_file)],
                                    text=True, capture_output=True, cwd=tmp)
            self.assertEqual(0, result.returncode, result.stderr)
            sections = re.findall(r'# ([\w-]+)\n([^\n]+)', result.stdout)
            config = {name: dict(pair.split('=', 1) for pair in line.split(';')) for name, line in sections}
            self.assertEqual({'product-service', 'inventory-service', 'order-service',
                              'notification-service', 'api-gateway'}, set(config))
            for service, settings in config.items():
                self.assertEqual('1.0', settings['TRACING_SAMPLING_PROBABILITY'])
            for service, password in [('order-service', 'order-amqp'), ('notification-service', 'notification-amqp'),
                                      ('inventory-service', 'inventory-amqp')]:
                self.assertEqual(service, config[service]['SPRING_RABBITMQ_USERNAME'])
                self.assertEqual(password, config[service]['SPRING_RABBITMQ_PASSWORD'])
                self.assertEqual('ecommerce', config[service]['SPRING_RABBITMQ_VIRTUAL_HOST'])
            self.assertEqual('redis-local', config['notification-service']['REDIS_PASSWORD'])
            self.assertFalse((Path(tmp) / 'arquivo-nao-deve-existir').exists())


class RabbitInitTest(unittest.TestCase):
    def run_init(self, fail=False, missing=False):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        bin_dir = Path(tmp.name)
        log = bin_dir / 'calls.jsonl'
        curl = bin_dir / 'curl'
        curl.write_text("#!/usr/bin/env python3\n" + r"""
import json, os, sys
from urllib.parse import urlsplit
payload = json.loads(sys.stdin.read())
with open(os.environ['TEST_CALLS'], 'a') as output:
    output.write(json.dumps([urlsplit(sys.argv[-1]).path, payload]) + '\n')
if os.environ['TEST_FAIL'] == 'yes':
    sys.exit(22)
""")
        curl.chmod(0o700)
        secrets = {'RABBITMQ_ADMIN_USERNAME': 'console-admin', 'RABBITMQ_ADMIN_PASSWORD': 'admin-local',
                   'RABBITMQ_ORDER_PASSWORD': 'order-"\\\n\tç',
                   'RABBITMQ_NOTIFICATION_PASSWORD': 'notification-local',
                   'RABBITMQ_INVENTORY_PASSWORD': 'inventory-local'}
        if missing:
            secrets['RABBITMQ_NOTIFICATION_PASSWORD'] = ''
        result = subprocess.run(['sh', str(ROOT / 'scripts/rabbitmq-init.sh')], text=True, capture_output=True,
                                env={**os.environ, **secrets, 'PATH': f'{bin_dir}:{os.environ["PATH"]}', 'TEST_CALLS': str(log),
                                     'TEST_FAIL': 'yes' if fail else 'no'})
        calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        return result, calls, secrets

    def test_cria_vhost_usuarios_sem_tags_e_permissoes_restritas_sem_expor_segredos(self):
        result, calls, secrets = self.run_init()
        self.assertEqual(0, result.returncode, result.stderr)
        payloads = dict(calls)
        self.assertIn('/api/vhosts/ecommerce', payloads)
        expected = {
            'order-service': ('RABBITMQ_ORDER_PASSWORD',
                              {'configure': r'^(order|inventory)\.', 'write': r'^(order|inventory)\.',
                               'read': r'^(order|inventory)\.'}),
            'notification-service': ('RABBITMQ_NOTIFICATION_PASSWORD',
                                     {'configure': '^$', 'write': '^$',
                                      'read': r'^order\.(created|cancelled)\.queue$'}),
            'inventory-service': ('RABBITMQ_INVENTORY_PASSWORD',
                                  {'configure': '^$', 'write': '^$',
                                   'read': r'^inventory\.order\.cancelled\.queue$'}),
        }
        for service, (key, permissions) in expected.items():
            self.assertEqual({'password': secrets[key], 'tags': ''}, payloads[f'/api/users/{service}'])
            self.assertEqual(permissions, payloads[f'/api/permissions/ecommerce/{service}'])
            self.assertNotIn(secrets[key], result.stdout + result.stderr)
        self.assertNotIn(secrets['RABBITMQ_ADMIN_PASSWORD'], result.stdout + result.stderr)
        self.assertIn({'configure': '(?!)', 'write': '(?!)', 'read': '(?!)'}, payloads.values())

    def test_falha_do_transporte_interrompe_provisionamento(self):
        result, calls, _ = self.run_init(fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(calls))

    def test_senha_ausente_falha_antes_de_chamar_api(self):
        result, calls, _ = self.run_init(missing=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)


class SeedKeycloakAdminTest(unittest.TestCase):
    def run_seed(self, existing_user=False, has_admin_role=False, missing=None):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        bin_dir = Path(tmp.name)
        log = bin_dir / 'kcadm-calls.jsonl'
        kcadm = bin_dir / 'kcadm.sh'
        kcadm.write_text("#!/usr/bin/env python3\n" + r"""
import json, os, sys
args = sys.argv[1:]
with open(os.environ['TEST_KCADM_CALLS'], 'a') as output:
    output.write(json.dumps(args) + '\n')
if args[:2] == ['get', 'users']:
    if os.environ.get('TEST_EXISTING_USER') == 'yes':
        print('"user-id-123"')
elif args[:2] == ['create', 'users']:
    print('user-id-123')
elif args[:2] == ['get', 'users/user-id-123/credentials']:
    if os.environ.get('TEST_EXISTING_USER') == 'yes':
        print('"cred-id-456","password"')
        print('"otp-id-789","otp"')
elif args[:1] == ['get-roles']:
    print('CUSTOMER')
    if os.environ.get('TEST_HAS_ADMIN') == 'yes':
        print('ADMIN')
""")
        kcadm.chmod(0o700)
        env_vars = {
            'KEYCLOAK_ADMIN_PASSWORD': 'master-secret-password',
            'KC_ADMIN_USER_EMAIL': 'admin@ecommerce.local',
            'KC_ADMIN_USER_PASSWORD': 'Admin_User.Pass-2026xxxx',
            'KCADM_BIN': str(kcadm),
            'TEST_KCADM_CALLS': str(log),
            'TEST_EXISTING_USER': 'yes' if existing_user else 'no',
            'TEST_HAS_ADMIN': 'yes' if has_admin_role else 'no',
        }
        if missing:
            env_vars[missing] = ''
        result = subprocess.run(
            ['sh', str(ROOT / 'scripts/dev/seed/seed-keycloak-admin.sh')],
            text=True, capture_output=True, env={**os.environ, **env_vars},
        )
        calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        return result, calls, env_vars

    def test_cria_usuario_define_senha_e_atribui_role_admin_sem_expor_segredos(self):
        result, calls, env_vars = self.run_seed()
        self.assertEqual(0, result.returncode, result.stderr)
        commands = [call[0] if call[0] != 'config' else ' '.join(call[:2]) for call in calls]
        self.assertEqual(
            ['config credentials', 'get', 'create', 'set-password', 'get-roles', 'add-roles'],
            commands,
        )
        config_idx = calls[0].index('--config') + 1
        config_path = Path(calls[0][config_idx])
        self.assertFalse(config_path.exists())
        self.assertFalse(config_path.parent.exists())
        create_call = calls[2]
        for expected in (
            'username=admin@ecommerce.local', 'email=admin@ecommerce.local',
            'enabled=true', 'emailVerified=true', 'requiredActions=[]',
        ):
            self.assertIn(expected, create_call)
        set_pw_call = calls[3]
        self.assertIn('--username', set_pw_call)
        self.assertIn('admin@ecommerce.local', set_pw_call)
        self.assertIn(env_vars['KC_ADMIN_USER_PASSWORD'], set_pw_call)
        add_role_call = calls[5]
        self.assertIn('ADMIN', add_role_call)
        output = result.stdout + result.stderr
        self.assertNotIn(env_vars['KEYCLOAK_ADMIN_PASSWORD'], output)
        self.assertNotIn(env_vars['KC_ADMIN_USER_PASSWORD'], output)

    def test_reexecucao_e_idempotente_e_rotaciona_senha_sem_recriar_usuario_ou_role(self):
        result, calls, env_vars = self.run_seed(existing_user=True, has_admin_role=True)
        self.assertEqual(0, result.returncode, result.stderr)
        commands = [call[0] if call[0] != 'config' else ' '.join(call[:2]) for call in calls]
        self.assertNotIn('create', commands)
        self.assertNotIn('add-roles', commands)
        self.assertIn('set-password', commands)
        deleted = [call[1] for call in calls if call[0] == 'delete']
        self.assertEqual(['users/user-id-123/credentials/cred-id-456'], deleted)
        output = result.stdout + result.stderr
        self.assertNotIn(env_vars['KEYCLOAK_ADMIN_PASSWORD'], output)
        self.assertNotIn(env_vars['KC_ADMIN_USER_PASSWORD'], output)

    def test_variavel_obrigatoria_ausente_falha_antes_de_chamar_kcadm(self):
        for var in ('KEYCLOAK_ADMIN_PASSWORD', 'KC_ADMIN_USER_EMAIL', 'KC_ADMIN_USER_PASSWORD'):
            with self.subTest(var=var):
                result, calls, _ = self.run_seed(missing=var)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], calls)


if __name__ == '__main__':
    unittest.main()
