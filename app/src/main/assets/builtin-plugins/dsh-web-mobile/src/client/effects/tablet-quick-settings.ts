// 宽屏触屏（平板 ≥1024）的一键设置齿轮（2026-10-29，owner 要求）。
//
// 手机档的齿轮由 effects/drawer-quick-settings.ts 注入，但它挂在全树协调器上，
// 而协调器只在 MOBILE_QUERY（≤1023 + 粗指针）武装；平板走宿主原生布局，协调器
// 整个不跑，于是平板只剩下「账户菜单里那份设置」——正是 owner 说的「原版是合在一起的」。
//
// 这里不把协调器整体放宽（stats-line / header-metrics 等任务在 ≥1024 会真的动 DOM，
// 平板是原生布局，不该被手机的注入改），只把**这一个任务**按宽屏触屏重新武装：
// 复用 createDrawerQuickSettingsTask 的全部逻辑（注入、复刻点击链、菜单项收尾、
// 连接条让位），自己用一条极轻的 MutationObserver 驱动 ensure()。
//
// 只观察 childList（行/菜单被 React 重建时重新挂载）；不观察 attributes，避免宿主
// 每帧的属性写入唤醒自己。ensure() 幂等且只做挂载/属性判定，不读几何。
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { installMobileEffect, TABLET_TOUCH_QUERY } from '../platform/breakpoints.ts'
import { createDrawerQuickSettingsTask } from './drawer-quick-settings.ts'

/** 宽屏触屏上补一颗和手机档一致的一键设置齿轮。 */
export function installTabletQuickSettings(ctx: ClientContext): void {
  installMobileEffect(
    ctx,
    'tablet-quick-settings',
    () => {
      const task = createDrawerQuickSettingsTask(ctx)
      task.ensure()

      // DSHA_TABLET_SETTINGS_OBSERVER_V1: streamed message/composer/session-list
      // changes cannot replace the account row or its portal menu. Inspect only
      // changed subtrees, never rescan the conversation merely to find a gear.
      const relevant = '[class*="triggerRow"], [role="menu"], [data-shell-overlay]'
      const touchesSettings = (record: MutationRecord): boolean => {
        const target = record.target instanceof Element ? record.target : record.target.parentElement
        if (target?.closest(relevant)) return true
        if (target?.closest('[data-chat-flow]')) return false
        return [...record.addedNodes, ...record.removedNodes].some((node) =>
          node instanceof Element && (node.matches(relevant) || node.querySelector(relevant) !== null))
      }
      let frame = 0
      let active = true
      const run = (): void => {
        frame = 0
        if (!active) return
        try {
          task.ensure()
        } catch (error) {
          // 与协调器同口径：任务失败不拖垮其它效果，错误交给 debug 徽章可见的 console。
          console.error('[dsh-web-mobile] tablet-quick-settings', error)
        }
      }
      const observer = new MutationObserver((records) => {
        if (!active || frame !== 0 || !records.some(touchesSettings)) return
        frame = requestAnimationFrame(run)
      })
      observer.observe(document.documentElement, { childList: true, subtree: true })

      return () => {
        active = false
        if (frame !== 0) cancelAnimationFrame(frame)
        frame = 0
        observer.disconnect()
        task.dispose()
      }
    },
    TABLET_TOUCH_QUERY,
  )
}
