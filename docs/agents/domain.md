# Domain Docs

## 探索前读取

采用 single-context 布局：

- 根目录 GLOSSARY.md：领域术语。
- docs/adr/：架构决策；读取与当前工作相关的 ADR。

文件尚不存在时，直接继续。
由 domain-modeling 在术语或决策明确后按需创建。

## 使用领域术语

Issue、规格、重构建议、假设和测试名称中的领域概念，
使用 GLOSSARY.md 定义的术语。

发现术语缺口时，判断是否符合项目用语，
并将需要明确的概念留给 domain-modeling。

## ADR 冲突

方案与已有 ADR 冲突时，明确指出对应 ADR 和重新讨论的理由。
