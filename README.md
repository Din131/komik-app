# VoraToon

Aplikasi Android pribadi untuk membaca komik di voratoon.co tanpa browser, dengan adblock bawaan.

## Unduh APK

Buka halaman **[Releases](../../releases/latest)**, unduh `Komiku.apk`, lalu install di HP
(izinkan "Install unknown apps" kalau diminta). Setelah terpasang, update berikutnya
akan ditawarkan otomatis setiap kali aplikasi dibuka.

## Fitur

- Langsung membuka situs, layar penuh tanpa address bar
- Blokir iklan berbasis domain (daftar [StevenBlack](https://github.com/StevenBlack/hosts) + daftar bawaan)
- Pop-up, tab baru, dan redirect ke situs lain diblokir
- Zoom dengan dua jari dan tarik ke bawah untuk refresh
- Login Google/Discord/dll. lewat jendela pop-up, login tersimpan
- Splash screen dan layar tidak mati selama membaca
- Cek update dari GitHub Releases setiap aplikasi dibuka

## Ganti alamat situs

Edit `start_url` di [`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml) lalu commit.
