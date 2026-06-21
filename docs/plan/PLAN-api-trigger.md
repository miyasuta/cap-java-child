# 計画：親pom更新 → 子ジョブ群を自動トリガ（CI/CD API の実行）

手順書 [GUIDE-central-build-setup.md](../guide/GUIDE-central-build-setup.md) のステップ5。
親pom（依存pin）を更新して push したら、**それを参照する子プロジェクトの CI/CD ビルドを自動で一括起動**する仕組みの計画。

> 本検証（SAP CI/CD トライアル）では **API 用プランが無く未実装**。本ドキュメントは実装計画（設計）として残す。
>
> ⚠️ **代替あり**：環境昇格（DEV/QAS/PROD）を持つ現場では、本書の独自トリガ（CI/CD API）は **[PLAN-promotion-model.md](PLAN-promotion-model.md)（親版固定＋自動テスト＋Renovate）で不要になる**。Renovate が新しい親版を検知して各子に PR を出し、merge→push が既存パイプラインを起動するため。新規採用なら promotion-model を先に検討すること。

## 1. やりたいこと

```
親repo に push（emoji-plugin.version 等を更新）
  → 親側の自動処理
      ① 親SNAPSHOT を publish（mvn deploy → GitHub Packages）
      ② 子プロジェクトN個の CI/CD ビルドを起動
  → 各子ジョブが mvn -U で再ビルド → 新しい親pin（＝新依存）を取り込み再デプロイ
```

`②` の「子ジョブを起動する」手段が本計画の主題。

## 2. 前提：SAP CI/CD API（サービスキー）が必須

子ジョブ（SAP CI/CD のジョブ）を外部から起動するには **SAP CI/CD の API** を使う。

- **API の有効化＋サービスキー作成**が必要：
  [Enabling API Usage](https://help.sap.com/docs/continuous-integration-and-delivery/sap-continuous-integration-and-delivery/optional-enabling-api-usage)
- サービスキーから得る値：`url`（APIベース）/ `tokenUrl` / `clientid` / `clientsecret`
- ⚠️ **トライアルは「trial (Application)」プランのみで、API 用のサービスインスタンスを作れない**。→ 有償プラン等、API を有効化できる環境が前提。

### API 呼び出しの流れ（共通・実行場所に依らない）

1. **OAuth2 client credentials でトークン取得**
   `POST {tokenUrl}`（Basic 認証 `clientid:clientsecret`, `grant_type=client_credentials`）→ `access_token`
2. **各子ジョブのビルドを起動**
   `POST {url}/v2/jobs/{jobName}/builds`（body に branch 等。`Authorization: Bearer <token>`）
   - ⚠️ 正確な path / body / 現行 API バージョンは**実機テナントの `/v2` OpenAPI で要確認**（サービスUIのURLのホスト名以降を `/v2` に置換すると見られる）。

## 3. 実行場所の選択肢：CI/CD ジョブ vs GitHub Actions

`①publish` ＋ `②API トリガ` をどこで実行するか。

### 選択肢A：SAP CI/CD の親ジョブ（追加ステップ）

親repo に SAP CI/CD ジョブを作り、`publish` 後の追加ステップで `trigger-children` を実行。

- 課題：
  - **`mvn deploy`（registry への publish）は SAP CI/CD の定義済みパイプラインが想定していない**（CFアプリのデプロイ前提）。Additional Commands 等で工夫が要る。さらに Additional Commands は node toolbox コンテナで動き **maven 不在**の可能性。
  - **publish の後に Node スクリプトを走らせる順序**の担保（post hook の有無）。
  - **ジョブ枠を消費**（トライアルは2個上限）。

### 選択肢B：GitHub Actions（親repo）

親repo に GitHub Actions workflow を置き、push（pin 変更）で発火。

```yaml
# .github/workflows/release-and-trigger.yml （概念）
on:
  push:
    branches: [main]
    paths: ['pom.xml']
jobs:
  release-and-trigger:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      # ① publish（GitHub Packages は Actions の GITHUB_TOKEN でネイティブ認証）
      - run: mvn -B clean deploy
        env: { GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }} }
      # ② SAP CI/CD API で子ジョブを起動
      - run: node ci/trigger-children.js
        env:
          CICD_API_URL:       ${{ secrets.CICD_API_URL }}
          CICD_TOKEN_URL:     ${{ secrets.CICD_TOKEN_URL }}
          CICD_CLIENT_ID:     ${{ secrets.CICD_CLIENT_ID }}
          CICD_CLIENT_SECRET: ${{ secrets.CICD_CLIENT_SECRET }}
          TARGET_BRANCH:      main
          JOB_NAMES:          cap-java-child
```

- 利点：
  - **GitHub Packages への publish が容易**（Actions 標準の `GITHUB_TOKEN` で認証。settings.xml も `actions/setup-java` が生成可）。
  - **外部API（SAP CI/CD）呼び出しが容易**（任意のスクリプト）。
  - **SAP CI/CD のジョブ枠を消費しない**（子ジョブだけ SAP CI/CD に残る）。
  - 親repo は GitHub 上にあるので自然。

### 比較

| 観点 | A: SAP CI/CD 親ジョブ | B: GitHub Actions |
|---|---|---|
| 親の publish（mvn deploy） | △ 工夫要（CFデプロイ前提・maven不在の懸念） | ◎ ネイティブに容易 |
| API トリガ実行 | ○ 追加ステップ（順序・コンテナ注意） | ◎ 任意スクリプトで容易 |
| SAP CI/CD ジョブ枠 | △ 親で1枠消費 | ◎ 消費しない |
| 「ビルド基盤は SAP CI/CD」制約 | ◎ 完全に CI/CD 内 | ○ 子ビルドは CI/CD、親オーケストレーションのみ Actions |
| 必要な秘密情報 | CI/CD 資格情報（write PAT＋APIキー） | GitHub Secrets（APIキー）。publish は標準 `GITHUB_TOKEN` |

### 推奨：**B（GitHub Actions）でオーケストレーション、子ビルドは SAP CI/CD のまま**

理由：①publish と②外部API呼び出しはいずれも Actions の方が素直。SAP CI/CD のジョブ枠も温存できる。「ビルド基盤は SAP CI/CD」という制約は**子のビルド＆デプロイが SAP CI/CD で動く**ことで満たされ、親側の「publish＋トリガ」はオーケストレーションに過ぎない。
ただし「親の処理も必ず SAP CI/CD 内で完結させたい」という要件があれば A を選ぶ。

## 4. `trigger-children` スクリプト設計（A/B 共通）

依存ゼロの Node 単一ファイル（Node 18+ の標準 `fetch`）。実行場所が A でも B でも中身は同じ。

### 入力（すべて環境変数／秘密情報。ハードコード禁止）

| 変数 | 内容 |
|---|---|
| `CICD_API_URL` | API ベースURL（サービスキーの `url`） |
| `CICD_TOKEN_URL` | OAuth トークンURL |
| `CICD_CLIENT_ID` / `CICD_CLIENT_SECRET` | サービスキー資格情報 |
| `TARGET_BRANCH` | 子をビルドするブランチ |
| `JOB_NAMES` | 対象ジョブ名の明示リスト（カンマ区切り）。**親自身は含めない**（無限ループ防止） |
| `CONCURRENCY` | 同時起動数（既定 5） |
| `DRY_RUN` | 既定 false（true なら起動せず対象だけ出力） |
| `FAIL_ON_ANY` | 既定 false（部分失敗を warn、ジョブは緑のまま） |

### 処理フロー

1. `POST {CICD_TOKEN_URL}`（Basic 認証, `grant_type=client_credentials`）→ `access_token`
2. `JOB_NAMES` を配列化（任意：`GET {CICD_API_URL}/v2/jobs` で実在チェック→タイプミス warn）
3. `CONCURRENCY` を上限に `POST {CICD_API_URL}/v2/jobs/{job}/builds`（body にブランチ等）
4. 起動成否を集計してサマリ出力。**fire-and-forget**（既定では完了待ちしない）
5. `FAIL_ON_ANY=true` のときのみ、1件でも失敗で非ゼロ終了

> 完了ゲート（全子が緑でないと親リリース不可）が要る場合は、後段に `GET .../builds/{n}` のポーリングを追加（親の実行時間は増える）。

## 5. 秘密情報の管理

| 用途 | A: SAP CI/CD | B: GitHub Actions |
|---|---|---|
| GitHub Packages publish（write） | CI/CD 資格情報（write:packages PAT）→ settings.xml | 標準 `GITHUB_TOKEN`（親repo の Packages へ write 可） |
| SAP CI/CD API キー | — | GitHub Secrets（`CICD_*`） |

> 注（B）：親pom の `distributionManagement` は親repo自身（`cap-java-parent-pom-sample`）の Packages を指すので、Actions の `GITHUB_TOKEN` でそのまま publish できる。

## 6. 未確定（実機で確認）

- [ ] SAP CI/CD API の有効化とサービスキー作成（要・非トライアル環境）
- [ ] `POST /v2/jobs/{jobName}/builds` の正確な path / body / 現行 API バージョン（実機 `/v2` OpenAPI）
- [ ] 対象ジョブ名（`JOB_NAMES`）の確定
- [ ] A 採用時：SAP CI/CD ジョブで `mvn deploy` を実行する手段／publish 後に Node を走らせる順序
- [ ] B 採用時：`actions/setup-java` での settings.xml 生成 or 明示 settings.xml の配置

## 参考

- [Enabling API Usage（SAP CI/CD）](https://help.sap.com/docs/continuous-integration-and-delivery/sap-continuous-integration-and-delivery/optional-enabling-api-usage)
- [PLAN-cicd-trigger-children.md](PLAN-cicd-trigger-children.md)（中央オーケストレーションの設計判断・対象リスト方式・実機メモ）
