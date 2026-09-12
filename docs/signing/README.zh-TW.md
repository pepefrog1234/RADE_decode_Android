# APK 正式簽章與 Google 套件登記

正式 APK 固定使用同一把 release 金鑰簽署，套件名稱為 `yakumo2683.RADEdecode`。之後每次建置與發佈都必須沿用這把金鑰；開發者帳號驗證、APK 簽章與 Google 套件登記是分開的步驟。

## 金鑰與備份

| 檔案 | 用途 | 是否可公開 |
|---|---|---|
| `.signing/rade-release.p12` | PKCS#12 私密金鑰，alias 為 `rade-release` | 不可 |
| `keystore.properties` | 本機金鑰路徑、alias 與密碼 | 不可 |
| [release-certificate.pem](release-certificate.pem) | 正式簽署金鑰的公開憑證 | 可以 |
| [release-certificate.sha256](release-certificate.sha256) | 公開憑證的 SHA-256 指紋 | 可以 |

**請將 `.signing/rade-release.p12` 與 `keystore.properties` 一起備份到安全、加密的位置。** 換電腦或重建開發環境時，還原這兩個檔案，切勿重新產生金鑰。遺失私密金鑰或密碼會影響後續更新能力；公開憑證無法還原私密金鑰。

私密檔案已加入 Git 忽略清單。不要提交到 Git、附加到 GitHub Release，或把密碼貼到聊天、Issue 或建置紀錄。公開憑證與指紋可以提交，供 Google 登記與 APK 驗證使用。

## 本機建置

專案根目錄的 `keystore.properties` 使用以下欄位；密碼應直接在本機設定，不要使用範例文字取代現有密碼：

```properties
storeFile=.signing/rade-release.p12
storePassword=<本機 keystore 密碼>
keyAlias=rade-release
keyPassword=<本機金鑰密碼>
```

下列環境變數可覆寫對應的本機設定，供 CI 或其他安全的憑證管理方式使用：

| 環境變數 | 對應欄位 |
|---|---|
| `RADE_RELEASE_STORE_FILE` | `storeFile` |
| `RADE_RELEASE_STORE_PASSWORD` | `storePassword` |
| `RADE_RELEASE_KEY_ALIAS` | `keyAlias` |
| `RADE_RELEASE_KEY_PASSWORD` | `keyPassword` |

建置使用 JDK 21。在專案根目錄執行：

```bash
./gradlew assembleRelease
./scripts/verify-release-apk.sh app/build/outputs/apk/release/app-release.apk
```

正式 APK 位於 `app/build/outputs/apk/release/app-release.apk`。Gradle 會先檢查 keystore 憑證是否符合版本庫中的 SHA-256 指紋。驗證指令再檢查實際 APK 的簽章、相同憑證指紋、套件名稱與非 debug 狀態；需要 Android SDK Build Tools 的 `apksigner`，以及同一目錄內的 `aapt`。必要時設定 `ANDROID_HOME` 為 SDK 目錄，或設定 `APKSIGNER` 為該執行檔的完整路徑。

缺少簽章設定時，release 建置會失敗，不會改用 debug 金鑰或產生可發佈的未簽章版本。日常開發仍可執行 `./gradlew assembleDebug` 與 `./gradlew testDebugUnitTest`。`bundleRelease`、包含 release 的完整建置也需要正式簽章設定。

## GitHub Actions

在 GitHub 儲存庫的 **Settings → Secrets and variables → Actions** 設定以下 repository secrets，內容必須來自同一把本機正式金鑰：

| Secret | 內容 |
|---|---|
| `RADE_RELEASE_KEYSTORE_BASE64` | `.signing/rade-release.p12` 的 Base64 編碼 |
| `RADE_RELEASE_STORE_PASSWORD` | `storePassword` |
| `RADE_RELEASE_KEY_ALIAS` | `rade-release` |
| `RADE_RELEASE_KEY_PASSWORD` | `keyPassword` |

Base64 只是編碼，仍屬私密金鑰，必須以 secret 保存。工作流程會在暫存目錄還原 keystore，以正式金鑰建置並驗證 APK，再上傳產物。新的 GitHub Release 僅提供正式簽章 APK；debug APK 只保留為 CI 測試產物。若指定的舊 Release 還包含 debug／unsigned APK，工作流程會要求改用新 tag，保留歷史版本。缺少簽章 secrets 時，正式發佈必須失敗，不可改上傳 debug 或 unsigned APK。

## Google 開發者驗證與套件登記

通過開發者身分驗證後，仍需在適用的 Google 開發者主控台登記 `yakumo2683.RADEdecode` 與正式簽章憑證。簽好 APK 不代表 Google 已完成登記；以主控台顯示的登記狀態為準。

若已有 Google Play Console 帳號，可在該主控台處理開發者驗證與非 Play App 的登記；若只在 Google Play 以外發佈且沒有 Play Console 帳號，使用 Android Developer Console。依主控台操作加入套件名稱與上方的 SHA-256 憑證指紋。Google 對已存在的套件名稱會根據已知安裝與簽署金鑰判定資格，因此新建立的正式金鑰可能不在可直接登記的清單中，屆時需依主控台提出套件使用申請。不要因為指紋未列出就更換這把正式金鑰。參考 [Google Play Console 登記說明](https://developer.android.com/developer-verification/guides/google-play-console)及 [Android Developer Console 套件登記說明](https://support.google.com/android-developer-console/answer/16640821?hl=en)。

若主控台要求 APK 所有權證明：

1. 複製主控台針對該帳號提供的驗證片段。
2. 在本機建立 `app/src/main/assets/adi-registration.properties`，原樣貼入該片段。
3. 重新執行上述 release 建置與 APK 驗證指令。
4. 將產生的 `app-release.apk` 上傳到主控台，完成其餘步驟並確認登記狀態。

`adi-registration.properties` 已加入 Git 忽略清單，請使用本機建置完成這項證明；目前 CI 不會注入該片段，不能直接以一般 CI 產物替代。片段必須取自主控台，不能自行猜測或編造。登記完成後可移除本機驗證片段，下次一般發佈再重新建置 APK。

## 舊版 debug APK 的更新

先前提供的 debug APK 與這次新增的正式金鑰不同，無法直接覆蓋更新為正式版本。Android 更新要求相同套件名稱與相容的簽署憑證；Google 套件登記不會變更這項限制。[Android 官方更新規則](https://developer.android.com/google/play/app-updates)

切換到首次正式簽章版本前，使用者應先另行保存需要的錄音、記錄資訊與設定，再解除安裝舊版、安裝正式 APK。**解除安裝會刪除 App 的本機資料。** 後續只要沿用這把正式金鑰與套件名稱，並維持適當的版本碼，就能正常更新。
