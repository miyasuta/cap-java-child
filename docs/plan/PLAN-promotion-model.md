# 計画：昇格モデル — 親版固定 ＋ 自動テスト ＋ Renovate

DEV/QAS/PROD の環境昇格を持つ現場で、**事前検証済みの共有ライブラリのバージョンアップを、手動確認ゼロで全 consumer に安全に自動ロールアウトする**ための運用モデルと移行計画。

> 背景の議論（SNAPSHOT 自動方式が環境昇格・DEV手動と噛み合わない／「自動化＝手動検証を自動検証に移す」）は会話および [PLAN-api-trigger.md](PLAN-api-trigger.md) を参照。本ドキュメントはデモで採った SNAPSHOT 自動方式に代わる**本番運用の推奨モデル**。

## 1. 狙い（要件）

- ライブラリ（emoji-plugin）は**一部の参照アプリで検証済み**＝ロールアウトの GO サイン。
- その版上げを、**各 consumer を毎回手動確認することなく**、全 consumer・全環境に自動展開したい。
- ただし **DEV だけ取り残さない**／**PROD に入っている版を特定・再現できる**こと。

## 2. ターゲット像

```
emoji-plugin を固定版でリリース (1.1.0)
   └ central-parent を固定版でリリース (2.1.0、emoji-plugin 1.1.0 を pin)
         ↓ Renovate が各子repoの <parent> 版更新を検知
   各 consumer repo に PR「<parent> 2.0.0 → 2.1.0」を自動作成
         ↓ PR で各アプリの自動テスト実行（＝そのアプリ固有の自動検証）
   緑なら auto-merge
         ↓ main への push（既存の webhook トリガ）
   各 consumer の CI/CD パイプライン: build → test → DEV → QAS → PROD（段階昇格）
```

要点：
- **全環境を CI/CD 管理**（DEV も載せる）→ 一貫性問題が消える。
- **検証は「手動」ではなく「各アプリのパイプライン自動テスト」**が担う。ライブラリ事前検証＝GO、consumer パイプライン＝自動の安全網。
- **再現性**：各デプロイが「`<parent>` 何版」という**明示コミットにひも付く**（PRODに何が入っているか git から特定可）。

## 3. 版トポロジーの変更（デモ → 本番）

| 対象 | デモ（採用済） | 本番（本計画） | 変更理由 |
|---|---|---|---|
| emoji-plugin | 固定版 1.0.0/1.1.0 | **同じ**（固定版） | 変更なし |
| central-parent | **SNAPSHOT** (1.0.0-SNAPSHOT) | **固定版**（2.0.0 → 2.1.0、emoji-plugin を pin） | バンプを可視・再現可能にする |
| 子の `<parent>` 参照 | SNAPSHOT（`-U` で追従） | **固定版**（Renovate が PR で更新） | バンプを git 変更＝昇格可能にする |
| トリガ | 手動 or CI/CD API | **Renovate の PR → merge → push** | API 不要・標準フローに乗る |

## 4. なぜ独自トリガ（CI/CD API）が不要になるか

SNAPSHOT 自動方式では「親更新→子を起こす」ために CI/CD API（or git-push）で**明示的にトリガ**する必要があった（[PLAN-api-trigger.md](PLAN-api-trigger.md)）。本モデルでは：

- **Renovate** が各子repoの `<parent>` 版を GitHub Packages レジストリと突き合わせ、新版を検知して **PR を自動作成**＝これがファンアウトのトリガ。
- PR の **auto-merge → main への push** が、各子の**既存の push webhook**で CI/CD パイプラインを起動。

→ **`trigger-children.js`／CI/CD API／サービスキーは不要**。トライアルの API 制約も回避できる。Renovate が「中央オーケストレータ」を肩代わりする。

## 5. 必要コンポーネントと実装タスク

### 5-1. 親（central-parent）：固定版リリース化

- `version` を `1.0.0-SNAPSHOT` → **固定版（例 `2.0.0`）**に。emoji-plugin の pin を明示版で持つ（現行通り）。
- **リリース手順**：emoji-plugin 新版が出たら、親の pin を上げて**新しい親固定版を publish**。
  - 自動化案：emoji-plugin の publish をトリガに、GitHub Actions で親の pin 更新 → 親版インクリメント → `mvn deploy`（GitHub Packages は Actions の `GITHUB_TOKEN` で publish 容易）。
  - 手動でも可（版採番＋ deploy）。

### 5-2. 子（consumer-*）

- `<parent>` を **固定版参照**（`<relativePath/>` 空・座標解決）に。SNAPSHOT 参照をやめる。
- **自動テストの整備**（本モデルの安全網の本体）：OData レベルの統合/受入テスト（CAP Java の integration test など）。**テストが薄いと「自動ロールアウト＝自動で壊す」**になるため、ここが最重要投資。
- 既存の settings.xml/PAT 解決（[GUIDE-central-build-setup.md](../guide/GUIDE-central-build-setup.md) ステップ4）はそのまま流用。

### 5-3. Renovate

- **対象**：各子repoの pom.xml `<parent>` 版（GitHub Packages Maven レジストリを監視）。
- **設定**（`renovate.json`／共有 preset）：
  - Maven レジストリ（`https://maven.pkg.github.com/miyasuta/*`）と**認証**（hostRules に `read:packages` PAT）。
  - **auto-merge**：`automerge: true`（PR の必須ステータスチェック＝CI テスト緑が条件）。
  - スケジュール／グルーピング（複数依存をまとめる等）。
- **ホスティング**：Mend Renovate（GitHub App, 簡単）か self-hosted runner。← 要決定。

### 5-4. CI/CD（環境昇格）

- **DEV を CI/CD に載せる**（現状の手動 DEV を pipeline 化）。
- **段階昇格 DEV→QAS→PROD** をパイプラインに定義（SAP CI/CD の acceptance/release ステージ等）。
- **PR 検証ゲート**：auto-merge の条件となる「PR でテストを走らせ GitHub にステータスを返す」仕組み。← 実装場所が論点（次節）。

## 6. PR 検証ゲートの実装場所（要決定）

Renovate の auto-merge は **GitHub のステータスチェック**を見る。子のビルドは SAP CI/CD。両者の接続方法：

| 案 | PR でテストを走らせる場所 | 備考 |
|---|---|---|
| A | **SAP CI/CD の PR ビルド（PR voter）** | SAP CI/CD が PR でビルド＆ステータスを GitHub に返す設定が要る。デプロイと同じ基盤で一貫。 |
| B | **GitHub Actions で `mvn test` のみ** | PR 検証だけ Actions（GitHub ネイティブのステータス）、デプロイは従来通り SAP CI/CD。auto-merge と相性良。 |

→ **B（PR テストは Actions、デプロイは SAP CI/CD）が auto-merge とは噛み合わせやすい**が、SAP CI/CD が PR voter を持つなら A で一本化も可。

## 7. ガードレール（安全網のまとめ）

1. **各 consumer の自動テスト**＝バンプがそのアプリで壊れないことの自動確認（赤なら merge されない）。
2. **段階昇格**＝DEV/QAS で先に赤を出させ、PROD への爆風を抑える。PROD のみ承認ゲート、DEV/QAS は自動、も可。
3. **再現性**＝各デプロイが明示版コミットにひも付くので、問題版の特定・ロールバック（前の `<parent>` 版へ revert）が容易。
4. **Renovate のグルーピング/スケジュール**＝一度に大量 PR が走らないよう制御。

## 8. 段階導入（移行ステップ）

1. **親を固定版化**：central-parent を `2.0.0` でリリース、emoji-plugin 1.x を pin。
2. **子を固定版参照に**：consumer の `<parent>` を `2.0.0` 固定に。
3. **自動テストの確認/整備**：consumer に OData レベルの自動テストがあるか棚卸し。無ければ追加（最重要）。
4. **Renovate 導入（1リポジトリで PoC）**：consumer-a で `<parent>` 更新 PR が自動で立つことを確認。auto-merge は最初オフで挙動確認。
5. **PR 検証ゲート接続**：PR でテスト→ステータス（案A/B）。緑で auto-merge を有効化。
6. **DEV を CI/CD に載せる＋段階昇格**を整備。
7. **親リリースの自動化**（任意）：emoji-plugin publish → 親版インクリメント＆ publish を Actions 化。
8. **複数 consumer に展開**。

## 9. 未確定・要決定

- [ ] Renovate ホスティング：Mend GitHub App か self-hosted か。
- [ ] PR 検証ゲートの実装場所：SAP CI/CD PR voter（A）か GitHub Actions（B）か。
- [ ] 親リリースの採番・トリガ：手動 or Actions 自動。
- [ ] auto-merge の適用範囲：全自動 or PROD のみ承認。
- [ ] **各 consumer の自動テスト充実度**（現場で要調査。本モデルの前提）。
- [ ] GitHub Packages を Renovate が認証付きで参照できる設定（hostRules の PAT）。

## 10. デモ資産との関係

- デモ（SNAPSHOT 自動方式）は**機構の実証**として価値があり、そのまま残す。本モデルは**本番運用の推奨**。
- 本モデルに寄せる場合の差分は §3・§8。`PLAN-api-trigger.md` の独自トリガは**本モデルでは不要**（§4）になる旨を相互参照。

## 参考

- Renovate: Maven manager / private registries（hostRules, registryUrls）
- [GUIDE-central-build-setup.md](../guide/GUIDE-central-build-setup.md)（親pom中央ビルドの基本構成）
- [PLAN-api-trigger.md](PLAN-api-trigger.md)（独自トリガ案。本モデルでは Renovate が代替）
