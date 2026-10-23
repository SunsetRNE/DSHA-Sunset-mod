// Fixed mobile bundle transformer. The legacy browser share flow is preserved;
// Android persistent files use metadata preparation followed by native streaming.
import { readFileSync } from 'node:fs';

export function applyFileTransferClient(source) {
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error('file transfer mobile anchor: ' + before.slice(0, 80));
    source = source.replace(before, after);
  };
  const controls = readFileSync(new URL('./client-controls.js', import.meta.url), 'utf8');
  replace('children: (0, jsx_runtime_1.jsx)(icon_compat_ts_1.IconFolderOpen, { size: 16 }) })] }));',
    `children: (0, jsx_runtime_1.jsx)(icon_compat_ts_1.IconFolderOpen, { size: 16 }) }), typeof window.DSHA?.openDownloads === 'function' && (0, jsx_runtime_1.jsx)('button', {
        type: 'button', 'data-mobile-nav': 'files', 'data-dsha-downloads': true,
        'aria-label': document.documentElement.lang?.startsWith('zh') ? '下载记录' : 'Downloads',
        onClick: () => window.DSHA.openDownloads().catch(error => alert(error.message)), children: '↓'
    })] }));`);
  replace('function FilePreviewShareButton({ absolutePath, sessionId, readFileRange, t }) {',
    'function LegacyFilePreviewShareButton({ absolutePath, sessionId, readFileRange, t }) {');
  replace('function FilesRowShare({ sessionId, readFileRange, t }) {',
    'function LegacyFilesRowShare({ sessionId, readFileRange, t }) {');
  replace('const ROW_BUTTON = \'file-share-row\';', "const ROW_BUTTON = 'file-share-row';\n" + controls);
  const anchor = "                scope.slots.inject('sidebar.right.tab.files.actions', () => scope.slots.register({";
  replace(anchor, `                scope.slots.inject('deliverables.file.actions', () => scope.slots.register({
                    name: 'deliverables.file.actions', id: 'dsha-file-delivery-actions', order: 90,
                }, file_share_controls_tsx_1.FileDeliveryActions)),
                scope.slots.inject('deliverables.review.file.actions', () => scope.slots.register({
                    name: 'deliverables.review.file.actions', id: 'dsha-file-review-actions', order: 90,
                }, file_share_controls_tsx_1.FileDeliveryActions)),
` + anchor);
  replace("exports.FilesRowShare = FilesRowShare;", "exports.FilesRowShare = FilesRowShare;\nexports.FileDeliveryActions = FileDeliveryActions;");
  replace("            const stops = [\n                scope.slots.inject('sidebar.right.tab.document.actions'", "            const stops = [\n                file_share_controls_tsx_1.installNativeFileActions(),\n                scope.slots.inject('sidebar.right.tab.document.actions'");
  replace('exports.FileDeliveryActions = FileDeliveryActions;', 'exports.FileDeliveryActions = FileDeliveryActions;\nexports.installNativeFileActions = installNativeFileActions;');
  return source;
}
