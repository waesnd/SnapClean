# SnapClean v0.1 — Android kaynak kodu

Bu proje Android Studio'da açılabilen Kotlin/Jetpack Compose başlangıç uygulamasıdır. APK **henüz derlenip cihazda test edilmemiştir**.

## Özellikler
- Snapchat veri dışa aktarımındaki `friends.html` dosyasının **Gönderilen Arkadaşlık İstekleri** bölümünü okur.
- SQLite içinde kullanıcı adını tekil anahtar olarak tutar; tekrar içe aktarmada işaretlenen durumlar korunur.
- Arama, filtreleme, kullanıcı adı kopyalama, manuel `İptal edildi` işaretleme ve geri alma.
- Çevrimdışı; INTERNET izni yok; Snapchat parolası istemez.

## Kurulum
1. Android Studio'da `SnapClean` klasörünü açın.
2. Gradle eşitlemesini çalıştırın (Android SDK 35, JDK 17).
3. `app` yapılandırmasını Android telefonda çalıştırın veya Build > Build APK(s) ile APK oluşturun.
4. Uygulamada `friends.html içe aktar` düğmesiyle Snapchat dışa aktarım dosyasını seçin.

## Güvenlik ve sınırlamalar
- `İptal edildi` düğmesi Snapchat'e komut göndermez; **yalnızca senin manuel doğrulamana dayanan yerel kayıt** oluşturur.
- Uygulama mevcut Snapchat istek durumunu bilemez ve otomatik iptal yapmaz.
- Veritabanı uygulamanın özel depolama alanındadır; uygulamayı kaldırınca veriler kaybolabilir.
- HTML içe aktarma sırasında uygulama ana iş parçacığında çalışır; büyük dosyalarda kısa süreli donma görülebilir. Üretim sürümünde arka plan iş parçacığına taşınmalıdır.
- Bu sürüm CSV, yedek dışa aktarma, toplu iptal veya erişilebilirlik otomasyonu içermez.
