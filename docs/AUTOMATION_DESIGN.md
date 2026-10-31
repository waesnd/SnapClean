# SnapClean otomasyon tasarımı (SD Maid SE modelinden uyarlama)

SD Maid SE, önbellek temizliğini bir erişilebilirlik servisiyle sistem ekranlarını gezerek yapar.
Aşağıda bu mimarinin SnapClean'e **fikir olarak** uyarlanan ve uyarlanmayan parçaları var.
SD Maid SE GPL-3.0 lisanslıdır; buradaki kod ona bakılarak değil, kendi yazımımızla hazırlanmıştır, kopya değildir.

| SD Maid SE | SnapClean karşılığı | Durum |
|---|---|---|
| Her iş bir `AutomationStep` (pencere kontrolü + düğme eylemi + kurtarma + zaman aşımı) | `Step` enum'u: PROFILE, OPEN_MENU, MANAGE, REMOVE_FRIEND, CONFIRM, VERIFY; her birinin zaman aşımı ve deneme sayısı var | Var |
| Eylemden önce hedef pencerenin gerçekten açıldığını bekler | Her adım, sonraki ekranın işareti görünmeden ilerlemez | Var |
| Deneme başına zaman aşımı; ikinci denemeden sonra bildirim panelini kapatma | Menü denemesi ≥2 olunca bildirim paneli kapatılır (Android 12+) | Bu sürümle eklendi |
| Kilit ekranı / ekran kapanınca otomasyonu iptal eder | `screenAvailable()`: ekran kapalı veya kilitliyse durur | Bu sürümle eklendi |
| Kurtarma düğümü: eylem başarısızsa ekranı düzelt, tekrar dene | Sohbet ekranı açılırsa başlığa dokunup profile geçer; her dokunuş sonrası profil kimlik satırı doğrulanır | Bu sürümle eklendi |
| Servisin gördüğü ağaç, `uiautomator` dökümünden farklı olabilir; hata ayıklamada servisin kendi dökümü kullanılır | "Teşhisi kopyala": servis kendi ağacını döker (arama sonucu, sohbet ekranı, hata anı) | Var, 2 yeni döküm noktası eklendi |
| `ACTION_CLICK` olmazsa hareket (gesture) ile dokunma; pencere dışına dokunmayı reddeder | `tapNode`: önce tıklama, sonra hareket; sınır dışı koordinatlar kısılır | Var |
| Etiketler sistem Ayarlar uygulamasının kendi kaynaklarından okunur (dil bağımsız) | Snapchat'in kaynakları gizlenmiş; bu yüzden etiketler elle, düzenlenebilir listede (TR/EN) | Uyarlanamaz |
| DPAD (yön tuşu) yedeği: ADB veya root ile `input keyevent` | Root/ADB gerektirir; SnapClean'de kullanılamaz | Uyarlanamaz |
| Birden fazla hedefte başarısızlık bütçesi (8) sonra vazgeçer, kalanlara devam eder | SnapClean ilk başarısızlıkta **durur** ve hesabı listeler (silme işlemi için daha güvenli) | Bilerek farklı |

## Neden Snapchat için aynı kolaylık yok
SD Maid, AOSP'nin "Uygulama bilgisi" ekranını sürer: yapısı ve metinleri belgelenmiş, sürümler arası kararlı.
Snapchat'in arayüzü özel çizilmiş, kimlikleri gizlenmiş ve her güncellemede değişebilir. Bu yüzden
SnapClean'de her yıkıcı adımdan önce profil kimlik satırı doğrulanır ve belirsizlikte işlem yapılmaz.
