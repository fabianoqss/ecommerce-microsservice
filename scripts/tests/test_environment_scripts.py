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
        self.template = '# exemplo\nA=troque-primeiro\nB=troque-segundo\nFIXO=localhost\nVAZIO=\nREF=${A}\n'
        (self.root / '.env.example').write_text(self.template)

    def run_script(self, *args, env=None):
        return subprocess.run(['bash', str(self.root / 'scripts/generate-env.sh'), *args],
                              text=True, capture_output=True, env=env)

    def test_substitui_todos_os_segredos_preserva_configuracao_e_protege_arquivo(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        values = dict(line.split('=', 1) for line in (self.root / '.env').read_text().splitlines() if '=' in line)
        for key in ('A', 'B'):
            self.assertRegex(values[key], r'^[0-9a-f]{48}$')
            self.assertNotIn(values[key], result.stdout + result.stderr)
        self.assertNotEqual(values['A'], values['B'])
        self.assertEqual('localhost', values['FIXO'])
        self.assertEqual('', values['VAZIO'])
        self.assertEqual('${A}', values['REF'])
        self.assertEqual(0o600, stat.S_IMODE((self.root / '.env').stat().st_mode))

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
                'EUREKA_USERNAME': 'discovery', 'EUREKA_PASSWORD': 'a12345',
                'TRACING_SAMPLING_PROBABILITY': '1.0', 'IGNORADO': '$(touch arquivo-nao-deve-existir)',
            }
            env_file.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
            result = subprocess.run(['bash', str(ROOT / 'scripts/ide-env.sh'), str(env_file)],
                                    text=True, capture_output=True, cwd=tmp)
            self.assertEqual(0, result.returncode, result.stderr)
            sections = re.findall(r'# ([\w-]+)\n([^\n]+)', result.stdout)
            config = {name: dict(pair.split('=', 1) for pair in line.split(';')) for name, line in sections}
            self.assertEqual({'eureka-server', 'product-service', 'inventory-service', 'order-service',
                              'notification-service', 'api-gateway'}, set(config))
            self.assertEqual('discovery', config['eureka-server']['EUREKA_USERNAME'])
            self.assertEqual('a12345', config['eureka-server']['EUREKA_PASSWORD'])
            for service, settings in config.items():
                self.assertEqual('1.0', settings['TRACING_SAMPLING_PROBABILITY'])
                if service != 'eureka-server':
                    self.assertEqual('http://discovery:a12345@localhost:8761/eureka/',
                                     settings['EUREKA_CLIENT_SERVICEURL_DEFAULTZONE'])
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
        # Substitui só o transporte: o script real ainda produz URL, JSON e controla a falha.
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
        # Produtor é dono da topologia; consumidores só leem as próprias filas.
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


if __name__ == '__main__':
    unittest.main()
