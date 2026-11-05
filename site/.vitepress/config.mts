import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

/**
 * AgentCLI 作品集展示页配置。
 *
 * 规格红线（R0-2）：4 页封顶、无多级导航、无全文搜索、无 API 文档、
 * 无博客/更新日志、无深色主题定制。请勿在此文件新增页面或导航层级。
 */
export default withMermaid(
  defineConfig({
    lang: 'zh-CN',
    title: 'AgentCLI',
    description: 'Java 17 从零手写的 ReAct 命令行 AI 编程助手 · 零新增依赖',

    // GitHub Pages 项目站：https://<user>.github.io/agentcli/
    base: '/agentcli/',

    lastUpdated: false,
    cleanUrls: true,
    ignoreDeadLinks: true,

    // 基础 SEO / OG 标签（R0-2 的 P2 加分项，一次写完不再展开）
    head: [
      ['meta', { name: 'author', content: 'Hongli-L' }],
      [
        'meta',
        {
          name: 'keywords',
          content: 'AgentCLI, ReAct, Java 17, AI Agent, CLI, MCP, LSP, RAG, 多 Agent 编排'
        }
      ],
      ['meta', { property: 'og:type', content: 'website' }],
      ['meta', { property: 'og:title', content: 'AgentCLI · ReAct 命令行 AI 编程助手' }],
      [
        'meta',
        {
          property: 'og:description',
          content: 'Java 17 从零手写的 ReAct 命令行 AI 编程助手 · 零新增依赖'
        }
      ],
      ['meta', { property: 'og:url', content: 'https://hongli-l.github.io/agentcli/' }],
      ['meta', { name: 'twitter:card', content: 'summary' }]
    ],

    themeConfig: {
      // 单层导航，仅 4 项，不做下拉与多级
      nav: [
        { text: '首页', link: '/' },
        { text: '架构概览', link: '/architecture' },
        { text: '快速开始', link: '/quickstart' },
        { text: '量化数据', link: '/benchmarks' }
      ],

      // 侧边栏与导航同构，同样只有 4 项
      sidebar: [
        {
          text: 'AgentCLI',
          items: [
            { text: '首页', link: '/' },
            { text: '架构概览', link: '/architecture' },
            { text: '快速开始', link: '/quickstart' },
            { text: '量化数据', link: '/benchmarks' }
          ]
        }
      ],

      socialLinks: [{ icon: 'github', link: 'https://github.com/Hongli-L/agentcli' }],

      // 全文搜索：规格明令禁止。
      // 故意不声明 themeConfig.search（VitePress 默认即不构建搜索索引），
      // 并在 theme/custom.css 中硬隐藏导航栏搜索占位（双保险）。

      outline: { level: [2, 3], label: '本页目录' },
      docFooter: { prev: '上一页', next: '下一页' },
      returnToTopLabel: '回到顶部',
      darkModeSwitchLabel: '主题',
      lightModeSwitchTitle: '切换到浅色模式',
      darkModeSwitchTitle: '切换到深色模式',

      footer: {
        message: 'MIT License · 构建期依赖 Node，产品运行时仍零新增依赖',
        copyright: 'Copyright © 2025 Hongli-L'
      }
    },

    markdown: {
      lineNumbers: false
    },

    // vitepress-plugin-mermaid 配置：跟随站点主题，不做额外定制
    mermaid: {
      theme: 'default',
      securityLevel: 'loose'
    }
  })
)
