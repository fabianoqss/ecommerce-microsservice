import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SHA = '0123456789abcdef0123456789abcdef01234567'


class ReleaseFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.base / 'clone com espaços'
        self.prod = self.root / 'scripts/prod'
        self.prod.mkdir(parents=True)
        self.script = self.prod / 'release.sh'
        origem = ROOT / 'scripts/prod/release.sh'
        self.assertTrue(origem.is_file(), 'release.sh ainda não existe')
        shutil.copy(origem, self.script)
        self.log_git = self.base / 'git.jsonl'
        self.log_deploy = self.base / 'deploy.json'
        self.git_bin = self.base / 'git com espaços'
        self.git_bin.write_text(f'#!{sys.executable}\n' + f'''
import json
import os
from pathlib import Path
import sys

with Path({str(self.log_git)!r}).open('a') as log:
    log.write(json.dumps({{'args': sys.argv[1:], 'env': dict(os.environ)}}) + '\\n')
os.execv({shutil.which('git')!r}, ['git', *sys.argv[1:]])
''')
        self.git_bin.chmod(0o700)
        self.deploy = self.base / 'deploy com espaços; literal'
        self.deploy.write_text(f'#!{sys.executable}\n' + '''
import json
import os
from pathlib import Path
import sys

mask = os.umask(0o077)
Path(os.environ['TEST_DEPLOY_LOG']).write_text(json.dumps({
    'args': sys.argv[1:], 'umask': mask, 'locale': os.environ.get('LC_ALL'),
}))
sys.exit(int(os.environ.get('TEST_DEPLOY_STATUS', '0')))
''')
        self.deploy.chmod(0o700)
        self.ambiente = {'PATH': os.defpath, 'HOME': str(self.base),
                         'GIT_BIN': str(self.git_bin), 'DEPLOY_SCRIPT': str(self.deploy),
                         'TEST_DEPLOY_LOG': str(self.log_deploy)}
        for nome in ('LD_PRELOAD', 'LD_LIBRARY_PATH', 'FAKEROOTKEY', 'FAKED_MODE',
                     'FAKEROOTDONTTRYCHOWN'):
            if nome in os.environ:
                self.ambiente[nome] = os.environ[nome]

    def executar(self, *args, ambiente=None):
        env = self.ambiente | (ambiente or {})
        return subprocess.run([str(self.script), *args], env=env, cwd=self.base,
                              text=True, capture_output=True, timeout=10)

    def chamadas_git(self):
        if not self.log_git.exists():
            return []
        return [json.loads(linha) for linha in self.log_git.read_text().splitlines()]


class ReleaseArgumentosTest(ReleaseFixture):
    def test_sha_invalido_e_recusado_antes_de_chamar_git(self):
        for sha in (SHA.upper(), SHA[:-1], SHA + '0', 'g' * 40,
                    SHA + '\n', '\n' + SHA, SHA + '\r', '', '--help',
                    ' ' + SHA, SHA + '; touch marcador'):
            with self.subTest(sha=repr(sha)):
                resultado = self.executar(sha)
                self.assertNotEqual(0, resultado.returncode)
                self.assertIn('==> SHA inválido', resultado.stderr)
                self.assertEqual('', resultado.stdout)
                self.assertEqual([], self.chamadas_git())
                self.assertFalse(self.log_deploy.exists())

    def test_exige_exatamente_um_argumento_sem_chamar_git(self):
        for args in ((), (SHA, SHA)):
            with self.subTest(args=args):
                resultado = self.executar(*args)
                self.assertNotEqual(0, resultado.returncode)
                self.assertIn('==> Uso:', resultado.stderr)
                self.assertEqual([], self.chamadas_git())
                self.assertFalse(self.log_deploy.exists())

    def test_script_e_executavel(self):
        self.assertTrue(stat.S_IMODE(self.script.stat().st_mode) & stat.S_IXUSR)


@unittest.skipIf(os.geteuid() == 0, 'Verifica a recusa somente quando executado sem root.')
class ReleaseSemRootTest(ReleaseFixture):
    def test_exige_root_sem_chamar_git_ou_deploy(self):
        resultado = self.executar(SHA)
        self.assertNotEqual(0, resultado.returncode)
        self.assertIn('==> Execute com sudo.', resultado.stderr)
        self.assertEqual([], self.chamadas_git())
        self.assertFalse(self.log_deploy.exists())


@unittest.skipUnless(os.geteuid() == 0, 'O release de produção exige root, sem bypass de EUID.')
class ReleaseIntegracaoTest(ReleaseFixture):
    def setUp(self):
        super().setUp()
        self.remoto = self.base / 'remoto.git'
        self.origem = self.base / 'origem'
        self.git(self.base, 'init', '--bare', '--initial-branch=main', str(self.remoto))
        self.git(self.base, 'init', '--initial-branch=main', str(self.origem))
        self.git(self.origem, 'config', 'user.name', 'Teste Release')
        self.git(self.origem, 'config', 'user.email', 'release@example.invalid')
        (self.origem / 'versionado').write_text('anterior\n')
        (self.origem / '.gitignore').write_text('ignorado\n')
        self.git(self.origem, 'add', '.')
        self.git(self.origem, 'commit', '-m', 'Anterior na main')
        self.anterior = self.git(self.origem, 'rev-parse', 'HEAD')
        (self.origem / 'versionado').write_text('topo\n')
        self.git(self.origem, 'commit', '-am', 'Topo na main')
        self.topo = self.git(self.origem, 'rev-parse', 'HEAD')
        self.git(self.origem, 'checkout', '-b', 'outra', self.anterior)
        (self.origem / 'versionado').write_text('outra branch\n')
        self.git(self.origem, 'commit', '-am', 'Fora da main')
        self.outra = self.git(self.origem, 'rev-parse', 'HEAD')
        self.git(self.origem, 'remote', 'add', 'origin', str(self.remoto))
        self.git(self.origem, 'push', 'origin', 'main', 'outra')
        script = self.script.read_bytes()
        modo = stat.S_IMODE(self.script.stat().st_mode)
        shutil.rmtree(self.root)
        self.git(self.base, 'clone', '--single-branch', '--branch', 'main',
                 '--no-local', str(self.remoto), str(self.root))
        self.git(self.root, 'fetch', 'origin', 'refs/heads/outra:refs/remotes/origin/outra')
        self.prod.mkdir(parents=True)
        self.script.write_bytes(script)
        self.script.chmod(modo)

    def git(self, diretorio, *args):
        env = {'PATH': os.defpath, 'HOME': str(self.base),
               'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
               'LC_ALL': 'C'}
        resultado = subprocess.run(['git', '-C', str(diretorio), *args], env=env,
                                   text=True, capture_output=True, timeout=10)
        self.assertEqual(0, resultado.returncode, resultado.stderr)
        return resultado.stdout.strip()

    def assert_repositorio(self, sha, conteudo):
        self.assertEqual(sha, self.git(self.root, 'rev-parse', 'HEAD'))
        self.assertEqual(sha, self.git(self.root, 'rev-parse', 'refs/heads/main'))
        self.assertEqual('main', self.git(self.root, 'branch', '--show-current'))
        self.assertEqual(conteudo, (self.root / 'versionado').read_text())

    def assert_recusado(self, resultado, mensagem, conteudo='topo\n'):
        self.assertNotEqual(0, resultado.returncode)
        self.assertIn('==> ' + mensagem, resultado.stderr)
        self.assertEqual('', resultado.stdout)
        self.assert_repositorio(self.topo, conteudo)
        self.assertFalse(self.log_deploy.exists())
        self.assertFalse(any('checkout' in chamada['args'] for chamada in self.chamadas_git()))

    def assert_deploy(self, resultado, sha, conteudo, status=0):
        self.assertEqual(status, resultado.returncode, resultado.stderr)
        self.assertEqual(f'==> Repositório em {sha}\n', resultado.stdout)
        self.assert_repositorio(sha, conteudo)
        registro = json.loads(self.log_deploy.read_text())
        self.assertEqual([sha], registro['args'])
        self.assertEqual(0o077, registro['umask'])
        self.assertEqual('C', registro['locale'])

    def test_commit_no_topo_da_main_chama_deploy(self):
        self.assert_deploy(self.executar(self.topo), self.topo, 'topo\n')

    def test_commit_anterior_permite_rollback_e_retorno_ao_topo(self):
        self.assert_deploy(self.executar(self.anterior), self.anterior, 'anterior\n')
        self.assert_deploy(self.executar(self.topo), self.topo, 'topo\n')

    def test_fetch_atualiza_origin_main_em_clone_single_branch(self):
        self.git(self.origem, 'checkout', 'main')
        (self.origem / 'versionado').write_text('novo topo\n')
        self.git(self.origem, 'commit', '-am', 'Novo topo remoto')
        novo = self.git(self.origem, 'rev-parse', 'HEAD')
        self.git(self.origem, 'push', 'origin', 'main')
        self.assert_deploy(self.executar(novo), novo, 'novo topo\n')
        self.assertEqual(novo, self.git(self.root, 'rev-parse', 'origin/main'))

    def test_commit_de_outra_branch_e_recusado_sem_checkout_ou_deploy(self):
        self.git(self.root, 'cat-file', '-e', self.outra + '^{commit}')
        self.assert_recusado(self.executar(self.outra), 'commit fora da main')

    def test_sha_inexistente_e_recusado(self):
        self.assert_recusado(self.executar('0' * 40), 'Commit inexistente')

    def test_objeto_que_nao_e_commit_e_recusado(self):
        blob = self.git(self.root, 'rev-parse', 'HEAD:versionado')
        self.assert_recusado(self.executar(blob), 'Commit inexistente')

    def test_sha_de_tag_anotada_e_recusado_mesmo_apontando_para_main(self):
        self.git(self.origem, 'tag', '-a', 'anotada', '-m', 'Tag anotada', self.anterior)
        self.git(self.origem, 'push', 'origin', 'refs/tags/anotada')
        self.git(self.root, 'fetch', 'origin', 'refs/tags/anotada:refs/tags/anotada')
        tag = self.git(self.root, 'rev-parse', 'refs/tags/anotada')
        self.assert_recusado(self.executar(tag), 'SHA não corresponde a um commit')

    def test_alteracao_local_versionada_e_preservada(self):
        (self.root / 'versionado').write_text('alteração local\n')
        self.assert_recusado(self.executar(self.anterior), 'Alterações locais',
                             conteudo='alteração local\n')

    def test_alteracao_no_index_e_preservada(self):
        (self.root / 'versionado').write_text('alteração staged\n')
        self.git(self.root, 'add', 'versionado')
        self.assert_recusado(self.executar(self.anterior), 'Alterações locais',
                             conteudo='alteração staged\n')
        self.assertEqual('M  versionado', self.git(self.root, 'status', '--porcelain',
                                                 '--untracked-files=no'))

    def test_env_untracked_e_arquivo_ignorado_nao_bloqueiam(self):
        original = 'SEGREDO=valor-privado\n'
        (self.root / '.env').write_text(original)
        (self.root / 'ignorado').write_text('preservar\n')
        resultado = self.executar(self.anterior)
        self.assert_deploy(resultado, self.anterior, 'anterior\n')
        self.assertEqual(original, (self.root / '.env').read_text())
        self.assertEqual('preservar\n', (self.root / 'ignorado').read_text())
        self.assertNotIn('valor-privado', resultado.stdout + resultado.stderr)

    def test_codigo_de_saida_do_deploy_e_propagado(self):
        resultado = self.executar(self.anterior, ambiente={'TEST_DEPLOY_STATUS': '37'})
        self.assert_deploy(resultado, self.anterior, 'anterior\n', status=37)

    def test_fetch_falho_preserva_repositorio(self):
        self.git(self.root, 'remote', 'set-url', 'origin', str(self.base / 'ausente.git'))
        self.assert_recusado(self.executar(self.anterior), 'Falha ao buscar')
        self.assertEqual(self.topo, self.git(self.root, 'rev-parse', 'origin/main'))

    def test_hooks_locais_e_configuracao_global_nao_sao_executados(self):
        hooks = self.base / 'hooks'
        hooks.mkdir()
        marcador = self.base / 'hook-executado'
        hook = hooks / 'post-checkout'
        hook.write_text(f'#!/bin/sh\ntouch "{marcador}"\n')
        hook.chmod(0o700)
        self.git(self.root, 'config', 'core.hooksPath', str(hooks))
        (self.base / '.gitconfig').write_text('[core]\n    bare = true\n')
        resultado = self.executar(self.anterior)
        self.assert_deploy(resultado, self.anterior, 'anterior\n')
        self.assertFalse(marcador.exists())

    def test_git_nao_herda_redirecionamento_de_repo_ou_configuracao(self):
        ambiente = {'GIT_DIR': str(self.origem / '.git'), 'GIT_WORK_TREE': str(self.origem),
                    'GIT_CONFIG_COUNT': '1', 'GIT_CONFIG_KEY_0': 'core.bare',
                    'GIT_CONFIG_VALUE_0': 'true', 'GIT_TERMINAL_PROMPT': '1'}
        resultado = self.executar(self.anterior, ambiente=ambiente)
        self.assert_deploy(resultado, self.anterior, 'anterior\n')
        for chamada in self.chamadas_git():
            with self.subTest(args=chamada['args']):
                args, env = chamada['args'], chamada['env']
                self.assertEqual(str(self.root), args[args.index('-C') + 1])
                self.assertIn('core.hooksPath=/dev/null', args)
                self.assertEqual('0', env['GIT_TERMINAL_PROMPT'])
                self.assertEqual('1', env['GIT_CONFIG_NOSYSTEM'])
                self.assertEqual('/dev/null', env['GIT_CONFIG_GLOBAL'])
                self.assertNotIn('GIT_DIR', env)
                self.assertNotIn('GIT_WORK_TREE', env)
                self.assertNotIn('GIT_CONFIG_COUNT', env)

    def test_deploy_padrao_e_derivado_do_caminho_do_script(self):
        padrao = self.prod / 'deploy.sh'
        shutil.copy(self.deploy, padrao)
        self.ambiente.pop('DEPLOY_SCRIPT')
        self.assert_deploy(self.executar(self.anterior), self.anterior, 'anterior\n')

    def test_git_padrao_funciona_sem_injecao(self):
        self.ambiente.pop('GIT_BIN')
        self.assert_deploy(self.executar(self.anterior), self.anterior, 'anterior\n')


if __name__ == '__main__':
    unittest.main()
