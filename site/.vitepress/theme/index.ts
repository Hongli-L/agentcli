import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import './custom.css'

/**
 * 直接复用 VitePress 默认主题，仅追加一份极简 CSS。
 * 规格禁止深色主题定制与主题重写，此处不做任何 Layout 覆盖。
 */
const theme: Theme = {
  extends: DefaultTheme
}

export default theme
