import fcntl
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
ANTIGA = 'a' * 40
NOVA = 'b' * 40
APPS = ['product-service', 'inventory-service', 'notification-service',
        'order-service', 'api-gateway']
SEGREDO = 'segredo-que-nao-pode-aparecer'
DOCKER_FAKE = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
root = Path(os.environ['FAKE_ROOT'])
tag = os.environ.get('IMAGE_TAG', '')
cenario = os.environ.get('FAKE_CENARIO', '')
estado = root / 'estado'

def ler_tag(arquivo):
    for linha in arquivo.read_text().splitlines():
        if linha.startswith('IMAGE_TAG='):
            return linha.split('=', 1)[1].strip("\"'")
    return ''

registro = {'args': args, 'image_tag': tag, 'tag_arquivo': ler_tag(root / '.env')}
if '--env-file' in args:
    temporario = Path(args[args.index('--env-file') + 1])
    registro['tag_validada'] = ler_tag(temporario)
    registro['modo'] = temporario.stat().st_mode & 0o777
    registro['dono'] = [temporario.stat().st_uid, temporario.stat().st_gid]
with open(os.environ['FAKE_LOG'], 'a') as log:
    log.write(json.dumps(registro) + '\n')

if args[0] == 'compose':
    assert args[1:3] == ['--project-directory', str(root)], args
    comando = args[3:]
    if comando[0] == '--env-file':
        assert comando[2:] == ['config', '--quiet'], comando
        if cenario == 'config' and tag == os.environ['FAKE_NOVA']:
            print(os.environ['FAKE_SEGREDO'], file=sys.stderr)
            sys.exit(1)
    elif comando[0] == 'pull':
        if cenario == 'pull':
            print(os.environ['FAKE_SEGREDO'], file=sys.stderr)
            sys.exit(1)
    elif comando[:2] == ['config', '--images']:
        for servico in comando[2:]:
            print(f'ghcr.io/exemplo/ecommerce-{servico}:{tag}')
    elif comando[0] == 'up':
        estado.write_text(tag)
        if cenario == 'up' and tag == os.environ['FAKE_NOVA']:
            sys.exit(1)
    elif comando[0] == 'ps' and '-q' in comando:
        if cenario != 'ausente' or tag != os.environ['FAKE_NOVA']:
            print('cid-' + comando[-1])
    elif comando[0] == 'ps':
        print('apps running')
    else:
        raise AssertionError(comando)
elif args[:2] == ['image', 'inspect']:
    if cenario == 'digest':
        sys.exit(1)
    print(args[-1].rsplit(':', 1)[0] + '@sha256:' + 'c' * 64)
elif args[0] == 'inspect':
    assert args[1] == '--format' and '.State.Status' in args[2] and '.State.Health' in args[2], args
    ativa = estado.read_text()
    falha = args[-1] == 'cid-order-service' and (
        cenario == 'rollback' or ativa == os.environ['FAKE_NOVA'])
    if falha and cenario in ('health', 'rollback'):
        print('running unhealthy')
    elif falha and cenario == 'exited':
        print('exited healthy')
    elif falha and cenario == 'starting':
        print('running starting')
    elif falha and cenario == 'sem_healthcheck':
        print('running missing')
    else:
        print('running healthy')
else:
    raise AssertionError(args)
'''


class DeployFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        destino = self.root / 'scripts/prod'
        destino.mkdir(parents=True)
        self.script = destino / 'deploy.sh'
        shutil.copy(ROOT / 'scripts/prod/deploy.sh', self.script)
        self.env_file = self.root / '.env'
        self.original = (f'COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml\n'
                         f'COMPOSE_PROFILES=monitoring,apps\nIMAGE_REGISTRY=ghcr.io/exemplo\n'
                         f'SEGREDO={SEGREDO}\nIMAGE_TAG={ANTIGA}\nFIXO=preservado\n')
        self.env_file.write_text(self.original)
        for nome in ('docker-compose.yml', 'docker-compose.prod.yml'):
            (self.root / nome).write_text('services: {}\n')
        self.log = self.root / 'calls.jsonl'
        self.docker = self.root / 'docker'
        self.docker.write_text(DOCKER_FAKE)
        self.docker.chmod(0o700)

    def executar(self, *args, cenario='', ambiente=None):
        env = {'PATH': os.environ['PATH'], 'DOCKER_BIN': str(self.docker),
               'DEPLOY_LOCK_FILE': str(self.root / 'deploy.lock'),
               'DEPLOY_HEALTH_TIMEOUT': '0', 'DEPLOY_HEALTH_INTERVAL': '0',
               'FAKE_ROOT': str(self.root), 'FAKE_LOG': str(self.log),
               'FAKE_NOVA': NOVA, 'FAKE_SEGREDO': SEGREDO, 'FAKE_CENARIO': cenario}
        for nome in ('LD_PRELOAD', 'LD_LIBRARY_PATH', 'FAKEROOTKEY', 'FAKED_MODE',
                     'FAKEROOTDONTTRYCHOWN'):
            if nome in os.environ:
                env[nome] = os.environ[nome]
        env.update(ambiente or {})
        return subprocess.run(['bash', str(self.script), *args], env=env,
                              cwd=self.root.parent, text=True, capture_output=True, timeout=10)

    def chamadas(self):
        return [json.loads(linha) for linha in self.log.read_text().splitlines()] if self.log.exists() else []

    def subidas(self):
        return [chamada for chamada in self.chamadas() if 'up' in chamada['args']]

    def assert_sem_segredos_ou_temporarios(self, resultado):
        self.assertNotIn(SEGREDO, resultado.stdout + resultado.stderr)
        self.assertEqual([], list(self.root.glob('.env.tmp-*')))


@unittest.skipUnless(os.geteuid() == 0, 'Requer root; também pode rodar sob fakeroot, sem privilégios reais.')
class DeployTest(DeployFixture):
    def test_tags_invalidas_sao_recusadas_sem_chamar_docker_ou_criar_lock(self):
        for tag in ('B' * 40, 'a' * 39, 'a' * 41, NOVA + '\n', NOVA + '\r', ' ' + NOVA):
            with self.subTest(tag=repr(tag)):
                resultado = self.executar(tag)
                self.assertNotEqual(0, resultado.returncode)
                self.assertEqual([], self.chamadas())
                self.assertFalse((self.root / 'deploy.lock').exists())
                self.assertEqual(self.original, self.env_file.read_text())

    def test_exige_exatamente_um_argumento(self):
        for args in ((), (NOVA, ANTIGA)):
            with self.subTest(args=args):
                self.assertNotEqual(0, self.executar(*args).returncode)
                self.assertEqual([], self.chamadas())

    def test_pull_falho_nao_altera_env(self):
        resultado = self.executar(NOVA, cenario='pull')
        self.assertNotEqual(0, resultado.returncode)
        self.assertEqual(self.original, self.env_file.read_text())
        self.assertEqual(1, len(self.chamadas()))
        self.assert_sem_segredos_ou_temporarios(resultado)

    def test_sucesso_preserva_linhas_protege_env_e_registra_digests(self):
        resultado = self.executar(NOVA, ambiente={'IMAGE_TAG': 'c' * 40})
        self.assertEqual(0, resultado.returncode, resultado.stderr)
        self.assertEqual(self.original.replace(ANTIGA, NOVA), self.env_file.read_text())
        self.assertEqual(0o600, stat.S_IMODE(self.env_file.stat().st_mode))
        self.assertEqual((0, 0), (self.env_file.stat().st_uid, self.env_file.stat().st_gid))
        chamadas = self.chamadas()
        self.assertEqual(['compose', '--project-directory', str(self.root), 'pull', *APPS], chamadas[0]['args'])
        self.assertEqual(ANTIGA, chamadas[0]['tag_arquivo'])
        digests = [c for c in chamadas if c['args'][:2] == ['image', 'inspect']]
        self.assertEqual(5, len(digests))
        for app in APPS:
            self.assertIn(f'ecommerce-{app}@sha256:' + 'c' * 64, resultado.stdout)
        validacao = next(c for c in chamadas if '--env-file' in c['args'])
        self.assertEqual((NOVA, 0o600, [0, 0]),
                         (validacao['tag_validada'], validacao['modo'], validacao['dono']))
        self.assertEqual(ANTIGA, validacao['tag_arquivo'])
        self.assertEqual(['compose', '--project-directory', str(self.root), 'up', '-d',
                          '--no-build', '--remove-orphans', '--no-deps', *APPS], self.subidas()[0]['args'])
        self.assertEqual(NOVA, self.subidas()[0]['image_tag'])
        self.assertEqual(['compose', '--project-directory', str(self.root), 'ps', *APPS], chamadas[-1]['args'])
        self.assertIn(NOVA, resultado.stdout)
        self.assert_sem_segredos_ou_temporarios(resultado)

    def test_healthcheck_falho_dispara_rollback_e_revalida_saude(self):
        resultado = self.executar(NOVA, cenario='health')
        self.assertNotEqual(0, resultado.returncode)
        self.assertEqual(self.original, self.env_file.read_text())
        self.assertEqual([NOVA, ANTIGA], [c['image_tag'] for c in self.subidas()])
        self.assertIn('order-service', resultado.stderr)
        self.assertIn('Rollback concluído', resultado.stderr)
        inspecoes = [c for c in self.chamadas() if c['args'][0] == 'inspect' and c['tag_arquivo'] == ANTIGA]
        self.assertEqual(5, len(inspecoes))
        self.assert_sem_segredos_ou_temporarios(resultado)

    def test_falha_do_rollback_e_informada_claramente(self):
        resultado = self.executar(NOVA, cenario='rollback')
        self.assertNotEqual(0, resultado.returncode)
        self.assertIn('Rollback também falhou', resultado.stderr)
        self.assertEqual(self.original, self.env_file.read_text())

    def test_primeiro_deploy_falho_nao_faz_rollback(self):
        self.env_file.write_text(self.original.replace(f'IMAGE_TAG={ANTIGA}\n', ''))
        resultado = self.executar(NOVA, cenario='health')
        self.assertNotEqual(0, resultado.returncode)
        self.assertIn(f'IMAGE_TAG={NOVA}\n', self.env_file.read_text())
        self.assertEqual([NOVA], [c['image_tag'] for c in self.subidas()])
        self.assertIn('não haverá rollback automático', resultado.stderr)
        self.assertIn('sem rollback', resultado.stderr)

    def test_env_nao_e_executado(self):
        marcador = self.root / 'marcador'
        maliciosa = f'IGNORADO=$(touch {marcador})\n'
        self.env_file.write_text(self.original + maliciosa)
        resultado = self.executar(NOVA)
        self.assertEqual(0, resultado.returncode, resultado.stderr)
        self.assertFalse(marcador.exists())
        self.assertIn(maliciosa, self.env_file.read_text())

    def test_recusa_env_simbolico(self):
        alvo = self.root / 'alvo'
        self.env_file.rename(alvo)
        self.env_file.symlink_to(alvo)
        resultado = self.executar(NOVA)
        self.assertNotEqual(0, resultado.returncode)
        self.assertEqual([], self.chamadas())
        self.assertTrue(self.env_file.is_symlink())
        self.assertEqual(self.original, alvo.read_text())

    def test_recusa_env_ausente_ou_diretorio(self):
        self.env_file.unlink()
        self.assertNotEqual(0, self.executar(NOVA).returncode)
        self.env_file.mkdir()
        self.assertNotEqual(0, self.executar(NOVA).returncode)
        self.assertEqual([], self.chamadas())

    def test_tag_anterior_invalida_ou_duplicada_impede_pull(self):
        for linha in ('IMAGE_TAG=invalidada', f'IMAGE_TAG="{ANTIGA}\'',
                      f'IMAGE_TAG={ANTIGA}\r', f'IMAGE_TAG={ANTIGA}\nIMAGE_TAG={NOVA}'):
            with self.subTest(linha=repr(linha)):
                conteudo = self.original.replace(f'IMAGE_TAG={ANTIGA}', linha)
                self.env_file.write_text(conteudo)
                self.assertNotEqual(0, self.executar(NOVA).returncode)
                self.assertEqual([], self.chamadas())
                self.assertEqual(conteudo.encode(), self.env_file.read_bytes())

    def test_aceita_tag_anterior_com_aspas_simetricas(self):
        for aspas in ("'", '"'):
            with self.subTest(aspas=aspas):
                self.env_file.write_text(self.original.replace(ANTIGA, aspas + ANTIGA + aspas))
                resultado = self.executar(NOVA)
                self.assertEqual(0, resultado.returncode, resultado.stderr)
                self.assertEqual(self.original.replace(ANTIGA, NOVA), self.env_file.read_text())

    def test_tag_igual_a_atual_ainda_faz_pull_e_recria_apps(self):
        resultado = self.executar(ANTIGA)
        self.assertEqual(0, resultado.returncode, resultado.stderr)
        self.assertEqual(1, len(self.subidas()))
        self.assertIn('já está ativa', resultado.stderr)

    def test_falhas_antes_da_troca_nao_alteram_env(self):
        for cenario in ('config', 'digest'):
            with self.subTest(cenario=cenario):
                resultado = self.executar(NOVA, cenario=cenario)
                self.assertNotEqual(0, resultado.returncode)
                self.assertEqual(self.original, self.env_file.read_text())
                self.assertEqual([], self.subidas())
                self.assert_sem_segredos_ou_temporarios(resultado)

    def test_falha_do_up_faz_rollback(self):
        resultado = self.executar(NOVA, cenario='up')
        self.assertNotEqual(0, resultado.returncode)
        self.assertEqual(self.original, self.env_file.read_text())
        self.assertEqual([NOVA, ANTIGA], [c['image_tag'] for c in self.subidas()])

    def test_sinal_apos_rename_tambem_faz_rollback(self):
        bin_dir = self.root / 'bin'
        bin_dir.mkdir()
        mv = bin_dir / 'mv'
        mv.write_text('''#!/usr/bin/env python3
import os
from pathlib import Path
import signal
import sys
assert sys.argv[1:3] == ['-fT', '--']
os.replace(sys.argv[3], sys.argv[4])
marcador = Path(os.environ['FAKE_ROOT']) / 'sinal-enviado'
if not marcador.exists():
    marcador.touch()
    os.kill(os.getppid(), signal.SIGTERM)
''')
        mv.chmod(0o700)
        resultado = self.executar(NOVA, ambiente={'PATH': str(bin_dir) + ':' + os.environ['PATH']})
        self.assertNotEqual(0, resultado.returncode)
        self.assertEqual(self.original, self.env_file.read_text())
        self.assertEqual([ANTIGA], [c['image_tag'] for c in self.subidas()])
        self.assertIn('Rollback concluído', resultado.stderr)
        self.assert_sem_segredos_ou_temporarios(resultado)

    def test_container_ausente_sem_healthcheck_ou_nao_saudavel_falha(self):
        for cenario in ('ausente', 'starting', 'exited', 'sem_healthcheck'):
            with self.subTest(cenario=cenario):
                resultado = self.executar(NOVA, cenario=cenario)
                self.assertNotEqual(0, resultado.returncode)
                self.assertEqual(self.original, self.env_file.read_text())
                self.assertIn('order-service', resultado.stderr)

    def test_lock_exclusivo_recusa_deploy_concorrente(self):
        with open(self.root / 'deploy.lock', 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            resultado = self.executar(NOVA)
        self.assertNotEqual(0, resultado.returncode)
        self.assertEqual([], self.chamadas())
        self.assertEqual(self.original, self.env_file.read_text())

    def test_primeiro_deploy_preserva_ultima_linha_sem_quebra(self):
        conteudo = self.original.replace(f'IMAGE_TAG={ANTIGA}\n', '').rstrip('\n')
        self.env_file.write_text(conteudo)
        resultado = self.executar(NOVA)
        self.assertEqual(0, resultado.returncode, resultado.stderr)
        self.assertEqual(conteudo + f'\nIMAGE_TAG={NOVA}\n', self.env_file.read_text())

    def test_configuracao_de_timeout_invalida_impede_pull(self):
        for nome, valor in (('DEPLOY_HEALTH_TIMEOUT', '-1'), ('DEPLOY_HEALTH_INTERVAL', 'x')):
            with self.subTest(nome=nome):
                self.assertNotEqual(0, self.executar(NOVA, ambiente={nome: valor}).returncode)
                self.assertEqual([], self.chamadas())


@unittest.skipIf(os.geteuid() == 0, 'Verifica a recusa somente quando executado sem root.')
class DeploySemRootTest(DeployFixture):
    def test_exige_root_sem_chamar_docker(self):
        resultado = self.executar(NOVA)
        self.assertNotEqual(0, resultado.returncode)
        self.assertIn('sudo', resultado.stderr)
        self.assertEqual([], self.chamadas())
        self.assertEqual(self.original, self.env_file.read_text())


if __name__ == '__main__':
    unittest.main()
