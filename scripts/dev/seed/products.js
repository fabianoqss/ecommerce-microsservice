const catalog = db.getSiblingDB('product_db');

const products = [
  ['670000000000000000000001', 'TKL Mechanical Keyboard', 'Tenkeyless mechanical keyboard with brown switches and RGB lighting', '349.90', 'Peripherals'],
  ['670000000000000000000002', 'Wireless Gaming Mouse', 'Wireless mouse with a 26,000 DPI sensor and 70-hour battery life', '279.90', 'Peripherals'],
  ['670000000000000000000003', 'USB Headset with Microphone', 'Closed-back headset with a noise-cancelling microphone', '219.90', 'Peripherals'],
  ['670000000000000000000004', '27" QHD 165 Hz Monitor', '2560x1440 IPS monitor, 165 Hz, 1 ms and HDR10', '1899.00', 'Monitors'],
  ['670000000000000000000005', '24" Full HD Monitor', '1920x1080 IPS monitor with thin bezels for office use', '799.00', 'Monitors'],
  ['670000000000000000000006', '14" Ryzen 7 Laptop', 'Lightweight laptop with 16 GB RAM and a 512 GB SSD', '4599.00', 'Computers'],
  ['670000000000000000000007', '1 TB NVMe SSD', 'PCIe 4.0 SSD with read speeds up to 7,000 MB/s', '459.90', 'Storage'],
  ['670000000000000000000008', '2 TB External Hard Drive', 'Portable USB 3.2 hard drive for backups and files', '429.90', 'Storage'],
  ['670000000000000000000009', '128 GB Smartphone', 'Smartphone with a 6.5" AMOLED display and a 50 MP camera', '1999.00', 'Phones'],
  ['670000000000000000000010', 'Bluetooth In-Ear Earbuds', 'Wireless earbuds with active noise cancellation and a charging case', '399.90', 'Audio'],
  ['670000000000000000000011', 'Portable Speaker', 'Waterproof Bluetooth speaker with 12-hour battery life', '329.90', 'Audio'],
  ['670000000000000000000012', 'Fitness Smartwatch', 'Watch with GPS, a heart rate monitor and 10-day battery life', '899.00', 'Wearables'],
  ['670000000000000000000013', 'Full HD Webcam', '1080p webcam with a stereo microphone for video calls', '249.90', 'Peripherals'],
  ['670000000000000000000014', 'Ergonomic Chair', 'Chair with adjustable lumbar support and a mesh backrest', '1299.00', 'Furniture'],
  ['670000000000000000000015', 'Electric Height-Adjustable Desk', 'Desk with electric height adjustment and position memory', '2499.00', 'Furniture'],
  ['670000000000000000000016', 'Wi-Fi 6 Router', 'AX3000 dual-band router with four antennas', '549.90', 'Networking'],
  ['670000000000000000000017', '7-in-1 USB-C Hub', 'Hub with 4K HDMI, a card reader and 100 W Power Delivery', '189.90', 'Accessories'],
  ['670000000000000000000018', '65 W GaN Charger', 'Compact charger with two USB-C ports and one USB-A port', '199.90', 'Accessories'],
  ['670000000000000000000019', 'Book: Clean Code', 'Classic guide to best practices for writing readable code', '89.90', 'Books'],
  ['670000000000000000000020', 'Book: Clean Architecture', 'Principles of software architecture and system design', '94.90', 'Books'],
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
        version: NumberLong(0),
      },
    },
    { upsert: true },
  );
  inserted += result.upsertedCount;
}

print(`seed products: ${inserted} inserted, ${products.length - inserted} already existed`);
