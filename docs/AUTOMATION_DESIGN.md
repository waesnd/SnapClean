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

## Tıklama mantığı (2.6)
İki tür "tıklama" vardır ve farklı şeyler yapar:

| Yöntem | Ne yapar | Ne zaman işe yarar | Tuzağı |
|---|---|---|---|
| `performAction(ACTION_CLICK)` | Düğüme "tıklandı" der; koordinata bakmaz | Düğüm (veya benzer boyutlu atası) tıklanabilirse | Dönen `true` yalnızca "istek kabul edildi" demektir, etkisi garanti değildir. Büyük bir kapsayıcıyı tıklarsa yine `true` döner |
| `dispatchGesture` (dokunuş) | O koordinata gerçek parmak gibi dokunur; en üstteki görünüm alır | Snapchat gibi özel çizilmiş, tıklanabilir işaretsiz görünümlerde (gerçek dökümde 1 düğüm dışında hiçbiri `C` değildi) | Koordinat neye denk geliyorsa onu tıklar: yanlış hedef seçilirse yanlış şey açılır |

Uygulanan kurallar:
1. `ACTION_CLICK` yalnızca düğümün kendisi ya da **benzer boyutta** bir atası (alanı en çok 12 kat, ekranın %35'inden küçük) tıklanabilirse kullanılır. Aksi halde hedefin kendi merkezine dokunulur.
2. Dokunuş süresi sistemin tap süresidir; yol 1 px'lik çizgidir (bazı cihazlarda sıfır uzunluklu yol dokunuş sayılmaz).
3. Başarı, `true` dönmesine göre değil, **sonraki ekranın işaretine** göre verilir. Her dokunuştan sonra ekran imzası karşılaştırılıp günlüğe "ekran değişti / DEĞİŞMEDİ" yazılır; hangi yöntemin kullanıldığı da yazılır.
4. Hedef seçimi yöntemden önemlidir: arama sonucu satırına (veya metnine) dokunmak SOHBET açar, profil için **avatara** dokunmak gerekir. Önce avatar denenir; olmazsa satır, ardından sohbet başlığı.
5. Üst üste 3 dokunuş iptal edilirse (kullanıcı ekrana dokunmuş olabilir) otomasyon durur.

## Gerçek akış (cihaz ekran görüntülerinden, Türkçe arayüz)
1. Profil: sağ üstte paylaş ve **⋯** düğmeleri (`unified_profile_icon_button_action_menu`). Karşılıklı arkadaşta altta "… tarihinden beri arkadaşsın" yazısı vardır.
2. ⋯ alt sayfası: "Arkadaşlığı Yönet ›", "Sohbet ve Bildirim Ayarları", … , "Profili Gönder…", altta "Tamamlandı".
3. "Arkadaşlığı Yönet" alt sayfası: başlık ("Arkadaşlığı Yönet | Daha Fazla Bilgi"), **Engelle**, **Şikayet Et**, **Arkadaşı Sil**, "Adı Düzenle", "Tamamlandı". Satırlar yalnızca ~130 px arayla dizilidir: yanlış satıra dokunmak Engelle/Şikayet açar.
4. Onay penceresi: "**<kullanıcıadı>** adlı kullanıcıyı arkadaşlıktan çıkarmak istediğinden emin misin?", **Kaldır**, "İptal et". Arkasındaki ekran Sohbet listesine dönmüş olabilir.

Bundan çıkan kurallar (2.7):
- **Onay penceresi hedefi anmalı:** "Kaldır"a yalnızca pencere metni hedef kullanıcı adını içeriyorsa basılır (profil kimlik satırı sayılmaz).
- **Yerleşim kararlılığı:** Menü düğmesi, "Arkadaşlığı Yönet", "Arkadaşı Sil" ve "Kaldır" konumu 350 ms değişmeden dokunulmaz (alt sayfa kayarken komşu satıra basmamak için).
- **Geçici belirsizlik:** İki alt sayfa geçişte aynı etiketi birlikte gösterebilir; belirsizlik 2,5 sn sürerse durur.
- **Silme sonrası doğrulama:** "Kaldır"dan sonra Snapchat profile dönmeyebilir. Profil aramayla yeniden açılır ve "Ekle" görülünce `done` yazılır; açık kalan "Tamamlandı" sayfası kapatılır.
- **Kuru çalıştırma:** Onay penceresinde "İptal et"e basılır, sonra kalan sayfa kapatılır.
