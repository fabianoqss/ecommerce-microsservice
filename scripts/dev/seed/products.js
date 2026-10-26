// Catálogo de demonstração, SÓ EM DEV (montado apenas pelo docker-compose.dev.yml).
// Idempotente e não destrutivo: insere o produto só se o _id ainda não existir,
// então edições feitas pela API nunca são sobrescritas.
// O _id é fixo porque o estoque (inventory.sql) usa o id do produto como sku_code.

const catalog = db.getSiblingDB('product_db');

const products = [
  ['670000000000000000000001', 'Teclado Mecânico TKL', 'Teclado mecânico tenkeyless com switches marrons e iluminação RGB', '349.90', 'Periféricos'],
  ['670000000000000000000002', 'Mouse Gamer Sem Fio', 'Mouse sem fio com sensor de 26.000 DPI e 70 horas de bateria', '279.90', 'Periféricos'],
  ['670000000000000000000003', 'Headset USB com Microfone', 'Headset fechado com cancelamento de ruído no microfone', '219.90', 'Periféricos'],
  ['670000000000000000000004', 'Monitor 27" QHD 165 Hz', 'Monitor IPS 2560x1440, 165 Hz, 1 ms e HDR10', '1899.00', 'Monitores'],
  ['670000000000000000000005', 'Monitor 24" Full HD', 'Monitor IPS 1920x1080 com bordas finas para escritório', '799.00', 'Monitores'],
  ['670000000000000000000006', 'Notebook 14" Ryzen 7', 'Notebook leve com 16 GB de RAM e SSD de 512 GB', '4599.00', 'Computadores'],
  ['670000000000000000000007', 'SSD NVMe 1 TB', 'SSD PCIe 4.0 com leitura de até 7.000 MB/s', '459.90', 'Armazenamento'],
  ['670000000000000000000008', 'HD Externo 2 TB', 'HD portátil USB 3.2 para backup e arquivos', '429.90', 'Armazenamento'],
  ['670000000000000000000009', 'Smartphone 128 GB', 'Smartphone com tela AMOLED de 6,5" e câmera de 50 MP', '1999.00', 'Celulares'],
  ['670000000000000000000010', 'Fone Bluetooth In-Ear', 'Fone sem fio com cancelamento ativo de ruído e estojo de carga', '399.90', 'Áudio'],
  ['670000000000000000000011', 'Caixa de Som Portátil', 'Caixa Bluetooth à prova d\'água com 12 horas de bateria', '329.90', 'Áudio'],
  ['670000000000000000000012', 'Smartwatch Fitness', 'Relógio com GPS, monitor cardíaco e 10 dias de bateria', '899.00', 'Wearables'],
  ['670000000000000000000013', 'Webcam Full HD', 'Webcam 1080p com microfone estéreo para videochamadas', '249.90', 'Periféricos'],
  ['670000000000000000000014', 'Cadeira Ergonômica', 'Cadeira com apoio lombar ajustável e encosto em tela', '1299.00', 'Móveis'],
  ['670000000000000000000015', 'Mesa Regulável Elétrica', 'Mesa com ajuste elétrico de altura e memória de posições', '2499.00', 'Móveis'],
  ['670000000000000000000016', 'Roteador Wi-Fi 6', 'Roteador dual band AX3000 com quatro antenas', '549.90', 'Redes'],
  ['670000000000000000000017', 'Hub USB-C 7 em 1', 'Hub com HDMI 4K, leitor de cartões e Power Delivery de 100 W', '189.90', 'Acessórios'],
  ['670000000000000000000018', 'Carregador GaN 65 W', 'Carregador compacto com duas portas USB-C e uma USB-A', '199.90', 'Acessórios'],
  ['670000000000000000000019', 'Livro: Código Limpo', 'Guia clássico de boas práticas para escrever código legível', '89.90', 'Livros'],
  ['670000000000000000000020', 'Livro: Arquitetura Limpa', 'Princípios de arquitetura de software e design de sistemas', '94.90', 'Livros'],
];

let inserted = 0;
for (const [id, name, description, price, category] of products) {
  const result = catalog.products.updateOne(
    { _id: ObjectId(id) },
    {
      $setOnInsert: {
        name,
        description,
        price: NumberDecimal(price),
        category,
        imageUrl: `https://picsum.photos/seed/ecommerce-${id.slice(-2)}/600/600`,
      },
    },
    { upsert: true },
  );
  inserted += result.upsertedCount;
}

print(`seed products: ${inserted} inseridos, ${products.length - inserted} já existiam`);
