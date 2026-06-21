# 手順書：昇格モデル（親版固定 × Renovate × PR自動テスト × auto-merge）

事前検証済みの共有ライブラリ（emoji-plugin）の版上げを、**各 consumer を手動確認することなく**自動でロールアウトするための構成手順。
親pom を固定版でリリースし、**Renovate が各子repoの `<parent>` 更新を検知 → PR 自動作成 → PR で自動テスト → 緑なら auto-merge → main への push で CI/CD 起動**、という連鎖を作る。

> 設計の背景・判断は [PLAN-promotion-model.md](../plan/PLAN-promotion-model.md) を参照。
> 親pom による中央ビルドの土台（settings.xml / PAT 解決など）は [GUIDE-central-build-setup.md](GUIDE-central-build-setup.md) を参照。

## 全体構成

```
emoji-plugin を固定版でリリース (1.1.0 → 1.2.0)
  └ central-parent を固定版でリリース (2.0.0 → 2.1.0、emoji-plugin を pin)
        ↓ Renovate が各子repoの <parent> 版更新を検知（GitHub Packages を監視）
  consumer repo に PR「<parent> 2.0.0 → 2.1.0」を自動作成
        ↓ PR で各アプリの自動テスト実行（= そのアプリ固有の自動検証）
  🟢 緑 → auto-merge          🔴 赤 → ブロック（マージされない＝安全網）
        ↓ main への push（既存の webhook トリガ）
  consumer の CI/CD パイプライン（build → test → DEV → QAS → PROD）
```

要点：
- **検証は「手動」ではなく「各アプリのパイプライン自動テスト」**が担う。ライブラリ事前検証＝GO、consumer の自動テスト＝自動の安全網。
- **再現性**：各デプロイが「`<parent>` 何版」という明示コミットにひも付く。

## 前提

- 子アプリは PR を自動マージするため、**自動テストを組み込んでおく**（これが安全網の本体。薄いと「自動ロールアウト＝自動で壊す」になる）。
- Renovate は **GitHub App（Mend ホスト）**でホスティングする。
- 親pom / emoji-plugin は GitHub Packages（Maven レジストリ）に固定版で publish 済み。

---

## ステップ1：Renovate の設定

### 1-1. Mend Renovate アプリをリポジトリにインストール

- https://github.com/apps/renovate を開く → **Install / Configure**
- アカウント `miyasuta` を選択
- **Only select repositories** → `cap-java-child` を選んで Install

<img width="828" alt="Renovate install" src="https://github.com/user-attachments/assets/fc5f6caa-14a9-4c06-8599-cb0fb0e0dd68" />

インストールすると、Renovate（Mend クラウド）がリポジトリをスキャンし、onboarding PR「Configure Renovate」を自動作成する（数分かかる）。

> **注意**：過去にインストールして **suspend（停止）**したことがあると、onboarding PR は作成されない。その場合は GitHub の Settings → Applications で Renovate を **Unsuspend** するか、`renovate.json` を手動で作成する。

### 1-2. GitHub のトークンを Renovate に登録

GitHub Packages（Maven）は読み取りにも認証が必要。Mend ホスト版では `renovate.json` への暗号化埋め込み（旧 `app.renovatebot.com/encrypt`）は**廃止**され、**Mend Developer Portal の UI でシークレットを登録**する方式になっている。

参照：https://docs.renovatebot.com/mend-hosted/credentials/

- **PAT を作成**：GitHub → Developer settings → Personal access tokens (classic) → スコープ **`read:packages`**
- **Mend に登録**：https://developer.mend.io → `cap-java-child`（または org）→ **Credentials** → シークレット追加
  - Name：`GITHUB_PACKAGES_TOKEN`
  - Value：上記 PAT

### 1-3. `renovate.json` で登録したトークンを参照

リポジトリ直下に `renovate.json` を作成（onboarding PR が無い場合は main に直接 PR で追加）。

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": ["config:recommended"],
  "packageRules": [
    {
      "matchManagers": ["maven"],
      "matchPackageNames": ["com.example.central:central-parent"],
      "registryUrls": ["https://maven.pkg.github.com/miyasuta/cap-java-parent-pom-sample"]
    },
    {
      "matchPackageNames": ["customer:consumer-a-parent"],
      "enabled": false
    }
  ],
  "hostRules": [
    {
      "matchHost": "maven.pkg.github.com",
      "hostType": "maven",
      "token": "{{ secrets.GITHUB_PACKAGES_TOKEN }}"
    }
  ]
}
```

設定の意味：

| 項目 | 役割 |
|---|---|
| `registryUrls`（central-parent ルール） | pom には `<repositories>` を書かない方針のため、**central-parent の取得先**（GitHub Packages）を Renovate に明示する。Renovate は settings.xml を読まない。 |
| `hostRules` の `{{ secrets.GITHUB_PACKAGES_TOKEN }}` | 1-2 で登録した Mend シークレットを参照し、`maven.pkg.github.com` を**認証付きで読む**。 |
| `customer:consumer-a-parent` を `enabled:false` | consumer 自身の集約pom（リアクタ内の親で publish されない）への「no-result」警告を抑止。 |

> 成功の確認：Mend のジョブログに `Found N new releases for com.example.central:central-parent` が出て **401/403 が出ない**こと（Recheck で即実行できる）。

---

## ステップ2：PR 時に自動テストが走るようにする

### 2-1. ワークフローを作成：`.github/workflows/pr-test.yml`

```yaml
name: PR Test

on:
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Run tests
        env:
          GITHUB_TOKEN: ${{ secrets.GH_PACKAGES_PAT }}
        run: mvn -B test -s settings.xml
```

- `-s settings.xml` で既存の設定（GitHub Packages の取得先 + 認証）を流用。
- `GITHUB_TOKEN` env に PAT を渡すと settings.xml の `${env.GITHUB_TOKEN}` が解決され、親pom・emoji-plugin を GitHub Packages から取得してビルド・テストできる。

### 2-2. Action 用のシークレットを登録

GitHub → `cap-java-child` → Settings → **Secrets and variables → Actions** → New repository secret
- Name：`GH_PACKAGES_PAT`
- Value：**`read:packages` 権限の PAT**（Renovate 用に作ったものを再利用可）

> **シークレットは2系統ある**：Mend 側の `GITHUB_PACKAGES_TOKEN`（Renovate がレジストリを読む用）と、Actions 側の `GH_PACKAGES_PAT`（CI ビルドが親pom/プラグインを解決する用）は**別管理**。同じ PAT 値でも双方に登録が要る。

### 2-3. ブランチ保護ルールを登録

main へのマージに `test` を必須化する。GitHub → Settings → **Rules / Branches** で main 向けルールセットを作成：
- **Require status checks to pass** を有効化
- 必須チェックに **`test`** を指定

<img width="787" alt="Branch protection required check" src="https://github.com/user-attachments/assets/a37ae9a4-3073-4753-a1ba-a5239f2d985e" />

> **`test` は job 名**（`jobs.test`）であって、ステップ名でも workflow 名でもない。
> GitHub Actions は **job 1つにつき check run を1つ**生成し、その **check run の名前 = job 名**になる。ブランチ保護はこの check run 名（`test`）で照合する。PR の Checks 欄では `PR Test / test`（`workflow名 / job名`）と表示されるが、必須指定の識別子は `test`。
> 必須チェック候補に `test` を出すには、**一度 workflow が走ってチェック名が GitHub に登録されている**必要がある（未実行だと検索しても出ない）。

### 2-4. PR 時に自動テストが走ることを確認

<img width="952" alt="PR test running" src="https://github.com/user-attachments/assets/6ad16a26-2e90-4057-abcc-5c7ff8aab048" />

---

## ステップ3：テスト → マージを自動化する

### 3-1. リポジトリで auto-merge を許可

GitHub → `cap-java-child` → Settings → **General → Pull Requests** → **Allow auto-merge** にチェック。

<img width="815" alt="Allow auto-merge" src="https://github.com/user-attachments/assets/af28264d-2149-449f-8af7-ab71aaf14295" />

> これが無いと、Renovate が auto-merge を仕掛けても GitHub 側が受け付けない。

### 3-2. `renovate.json` に `automerge` を追加

central-parent ルールに `"automerge": true` を足す。

```json
{
  "matchManagers": ["maven"],
  "matchPackageNames": ["com.example.central:central-parent"],
  "registryUrls": ["https://maven.pkg.github.com/miyasuta/cap-java-parent-pom-sample"],
  "automerge": true
}
```

> Renovate の既定は `automerge:false`。そのため **central-parent に `true` を足すだけ**で「central-parent は緑なら自動マージ／他は PR だけ（手動マージ）」になる。

### 3-3.（補足）対象を親pomだけに絞る場合

現在の設定では親pom 以外（npm など）も Renovate が PR を作る。親pom だけに絞るなら「全部無効化 → central-parent だけ有効化」の順で書く（後のルールが前を上書きする）。

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": ["config:recommended"],
  "packageRules": [
    { "matchPackageNames": ["*"], "enabled": false },
    {
      "matchPackageNames": ["com.example.central:central-parent"],
      "registryUrls": ["https://maven.pkg.github.com/miyasuta/cap-java-parent-pom-sample"],
      "automerge": true,
      "enabled": true
    }
  ],
  "hostRules": [
    { "matchHost": "maven.pkg.github.com", "hostType": "maven", "token": "{{ secrets.GITHUB_PACKAGES_TOKEN }}" }
  ]
}
```

### 「緑は自動マージ／赤はブロック」を成立させる3点セット

| 要素 | 役割 |
|---|---|
| Renovate `automerge: true` | 「緑なら自動マージしてよい」と指示 |
| GitHub「Allow auto-merge」 | 自動マージ機能を解禁 |
| ブランチ保護「`test` 必須」 | **緑まで待たせる**（無いと即マージされ危険） |

---

## ステップ4：テストが失敗したときの対応

ライブラリの新版で**契約が変わった**場合（例：付与する絵文字が変わった）、consumer の自動テストが赤になり PR はブロックされる（＝安全網が機能）。回復には **consumer のテストを新しい契約に追従**させる。

### 重要：テスト修正は「同じ Renovate PR」に乗せる

テスト修正と親バンプは結合している。どちらか一方だけを main に入れても赤になるため、**Renovate の PR ブランチにテスト修正コミットを足して**1つの PR で緑にする。

```bash
git checkout renovate/com.example.central-central-parent-2.x
# テストの assert を新しい契約に修正
git add srv/src/test/java/.../EmojiPluginITest.java
git commit -m "Update emoji assertion to match plugin 1.2.0"
git push origin renovate/com.example.central-central-parent-2.x
```

| マージ内容 | main の状態 | test | 結果 |
|---|---|---|---|
| テスト修正だけを main へ | 親は旧版（旧契約） | 🔴 | ブロック |
| 親バンプだけ（= Renovate PR 単体） | 新契約だが test は旧期待 | 🔴 | ブロック |
| **親バンプ ＋ テスト修正を一緒に** | 新契約 ＆ test も新期待 | 🟢 | マージ可 |

> これは「**新しい契約に consumer のテストが追従して初めて昇格できる**」という昇格モデルの安全特性そのもの。

> Renovate は手動コミットを尊重し、ブランチを勝手に rebase/上書きしない。

### workflow が走らない（dispatch 取りこぼし）ときの対処

push したのに `test` が「Expected — Waiting for status to be reported」のまま固まり、**Actions タブにその commit の run が出ていない**場合、`pull_request: synchronize` の dispatch を GitHub が取りこぼしている（一過性）。新しいイベントを起こし直して復旧する：

```bash
git commit --allow-empty -m "Trigger CI"
git push origin renovate/com.example.central-central-parent-2.x
```

- あるいは **PR を Close → Reopen**（`reopened` イベントで再 dispatch）。
- 「遅延」と「取りこぼし」の見分け：Actions タブにその commit の run が**存在するか**で判断（存在しなければ取りこぼし）。

---

## ステップ5：Webhook 経由で CI/CD が実行されるようにする

main ブランチへのマージ（push）をトリガに、デプロイパイプライン（build → test → DEV → QAS → PROD）が実行されるようにする。

<img width="709" alt="CI/CD webhook trigger 1" src="https://github.com/user-attachments/assets/2d4d60d7-ccee-45d1-8648-5217582b35ca" />

<img width="780" alt="CI/CD webhook trigger 2" src="https://github.com/user-attachments/assets/e8e836d6-63e7-4ec2-9291-735157afb2de" />

> PR の検証ゲート（ステップ2〜3）と、デプロイパイプライン（このステップ）は別基盤。PR テストは GitHub Actions（auto-merge と噛み合うネイティブステータス）、デプロイは SAP CI/CD（main への push を webhook で受けて起動）という分担。詳細は [PLAN-promotion-model.md](../plan/PLAN-promotion-model.md) §6 を参照。

---

## 動作確認（赤 → 緑の一連）

安全網が「破壊的変更を止め、追従すれば通す」ことを、実際の版上げで確認する。

### 第1幕：破壊的変更を安全網が止める

1. **emoji-plugin 1.2.0**：付与する絵文字を変更（例 `🚀` → `🎉`）して publish。
2. **central-parent 2.1.0**：emoji-plugin の pin を 1.2.0 に上げ、**親自身の `<version>` も 2.1.0 に**して publish。
   - （親自身の版を上げ忘れると、同一版の再 publish になり GitHub Packages が **409 Conflict** を返す。）
3. Renovate を Recheck → **「central-parent 2.0.0 → 2.1.0」PR が自動作成**される。
4. PR で `test` 実行 → consumer のテストは旧絵文字（`🚀`）を期待しているため **🔴 RED**。
5. 必須チェックが赤 → **auto-merge されず PR がブロック**＝破壊的変更を捕捉。

### 第2幕：契約に追従して通す

6. Renovate の PR ブランチで、テストの assert を新絵文字（`🎉`）に修正してコミット・push（ステップ4）。
7. `test` 再実行 → 親 2.1.0（`🎉`）＋ test も `🎉` 期待 → **🟢 GREEN**。
8. 必須チェック緑 → **auto-merge（squash）が発火 → main にマージ**。
9. main の `<parent>` が 2.1.0、テストが新契約に追従した状態で確定。
10. main への push を webhook が受け、CI/CD パイプラインが起動（ステップ5）。

→ 「事前検証済みライブラリの版上げを、手動確認なしで安全に展開」（[PLAN-promotion-model.md](../plan/PLAN-promotion-model.md) §1）が機構として回ることを確認できる。

## 参考

- [PLAN-promotion-model.md](../plan/PLAN-promotion-model.md)（背景・設計判断・移行ステップ）
- [GUIDE-central-build-setup.md](GUIDE-central-build-setup.md)（親pom中央ビルドの土台構成）
- Renovate Mend-hosted credentials: https://docs.renovatebot.com/mend-hosted/credentials/
