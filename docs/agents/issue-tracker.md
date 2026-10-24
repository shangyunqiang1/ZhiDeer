# Issue tracker: GitHub

Issues 和 specs 保存在 `shangyunqiang1/ZhiDeer` 的 GitHub Issues。
仓库地址：https://github.com/shangyunqiang1/ZhiDeer

## 操作约定

使用 gh CLI。每条命令显式传入
`--repo shangyunqiang1/ZhiDeer`，不依赖本地 Git remote。

- 创建：gh issue create --title "..." --body-file <file>
- 读取：gh issue view <number> --json number,title,body,labels,comments
- 列出：gh issue list --state open --json number,title,body,labels,comments
- 评论：gh issue comment <number> --body-file <file>
- 添加标签：gh issue edit <number> --add-label "<label>"
- 移除标签：gh issue edit <number> --remove-label "<label>"
- 关闭：gh issue close <number> --comment "..."

多行正文写入 UTF-8 临时文件，通过 --body-file 传入。
标签含义见 triage-labels.md。

当技能要求“发布到 issue tracker”时，创建 GitHub Issue。
当技能要求“获取相关 ticket”时，读取对应 GitHub Issue。

## Pull requests as a triage surface

**PRs as a request surface: no.**

## Wayfinding operations

- Map：一个带 wayfinder:map 标签的 Issue，
  正文包含 Notes、Decisions-so-far、Fog。
- 子任务：关联为 Map 的 GitHub sub-issue；
  不支持时，在 Map 中维护任务列表，并在子任务顶部写 Part of #<map>。
- 类型：使用 wayfinder:research、wayfinder:prototype、
  wayfinder:grilling、wayfinder:task 标签。
- 阻塞：优先使用 GitHub 原生 Issue dependencies；
  不支持时，在任务顶部写 Blocked by: #<number>。
  原生依赖 API 使用 Issue 数据库 id，不使用 Issue number 或 node_id。
- Frontier：按 Map 顺序选择未关闭、无未完成阻塞且未分配的子任务。
- Claim：开始工作前，将任务分配给执行者。
- Resolve：评论记录答案、关闭任务，并向 Map 的
  Decisions-so-far 添加摘要和链接。
