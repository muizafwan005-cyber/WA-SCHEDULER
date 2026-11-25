# Penjadwal WA (Android)

Aplikasi Android untuk mengirim pesan WhatsApp terjadwal, langsung dari HP tanpa server.

## Fitur

- Kirim ke **perorangan** (nomor) atau **grup** (nama grup)
- Lampiran **foto, video, audio, dan dokumen** (beberapa file sekaligus)
- Jadwal **sekali kirim, harian, mingguan, bulanan**
- Mendukung WhatsApp biasa dan WhatsApp Business
- Alarm tetap jalan setelah HP dinyalakan ulang
- Tombol **Kirim sekarang (uji coba)**: tahan lama pada sebuah jadwal

## Cara membuat APK lewat GitHub (tanpa Android Studio)

1. Buat repositori baru di GitHub, lalu unggah seluruh isi folder ini.
   ```bash
   git init && git add . && git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/USERNAME/wa-scheduler-android.git
   git push -u origin main
   ```
2. Buka tab **Actions**, workflow **Build APK** berjalan otomatis (sekitar 5 menit).
3. Setelah hijau, buka run tersebut, unduh artifact **wa-scheduler-apk**, ekstrak, dan pasang `app-debug.apk` di HP.
   (Izinkan "Pasang dari sumber tidak dikenal" saat diminta.)

Ingin APK muncul di halaman Releases? Beri tag: `git tag v1.0 && git push origin v1.0`.

Alternatif: buka folder ini di Android Studio, lalu **Build > Build APK(s)**.

## Setup pertama di HP

Buka aplikasi lalu ketuk **Atur izin yang belum aktif** berulang kali sampai semua bertanda centang:

1. **Layanan aksesibilitas**: aktifkan "Penjadwal WA". Dipakai untuk menekan tombol Kirim di WhatsApp.
2. **Tampil di atas aplikasi lain**: agar aplikasi bisa membuka WhatsApp otomatis dari latar belakang.
3. **Alarm tepat waktu**: agar pesan terkirim pada menit yang dijadwalkan.
4. **Bebas penghemat baterai**: agar alarm tidak ditunda sistem.

Di HP Xiaomi, Oppo, Vivo, Realme, dan Samsung, aktifkan juga **Mulai otomatis / Autostart** dan kunci aplikasi di Recent Apps, jika tidak alarm bisa dimatikan sistem.

## Cara kerja

Pada waktu jadwal, aplikasi menyalakan layar, membuka WhatsApp dengan pesan/lampiran yang sudah terisi,
lalu layanan aksesibilitas menekan tombol Kirim. Untuk grup, aplikasi mencari nama grup di daftar "Kirim ke"
dan memilihnya. Pengiriman dilakukan satu per satu bila ada beberapa jadwal bersamaan.

## Batasan yang perlu diketahui

- WhatsApp **tidak punya API resmi** untuk ini. Aplikasi bekerja dengan menekan tombol di layar,
  sehingga **bisa berhenti bekerja bila tampilan WhatsApp berubah** setelah pembaruan. Bila itu terjadi,
  perbaiki pencarian tombol di `WaAccessibilityService.kt` (fungsi `findSend` dan `pickGroup`).
- **Layar kunci**: HP bisa menyala sendiri, tetapi tidak bisa membuka PIN/pola/sidik jari. Agar jadwal
  malam hari berhasil, gunakan layar kunci "Geser" (tanpa PIN) atau pastikan HP tidak terkunci.
- Saat pengiriman berjalan (beberapa detik), layar akan menampilkan WhatsApp. Jangan menyentuh layar saat itu.
- **Nama grup** harus sama dengan di WhatsApp (huruf besar/kecil tidak berpengaruh).
- Pesan ke nomor yang tidak terdaftar di WhatsApp akan gagal dan tercatat di status jadwal.
- Jangan dipakai untuk spam. Pengiriman massal dapat membuat nomor dibatasi atau diblokir WhatsApp.
- APK yang dihasilkan adalah versi **debug** (bisa dipasang langsung, belum untuk Google Play).

## Struktur

```
app/src/main/java/com/wascheduler/app/
  MainActivity.kt          daftar jadwal + pengecekan izin
  EditActivity.kt          form buat/ubah jadwal
  Schedule.kt, Store.kt    model dan penyimpanan
  Scheduler.kt             hitung waktu berikutnya + AlarmManager
  AlarmReceiver.kt         dipanggil saat waktunya tiba
  BootReceiver.kt          pasang ulang alarm setelah restart
  SendCoordinator.kt       antrean dan pengatur pengiriman
  WaUtil.kt                menyusun Intent WhatsApp
  WaAccessibilityService.kt  menekan tombol di WhatsApp
.github/workflows/build-apk.yml   build APK otomatis
```
