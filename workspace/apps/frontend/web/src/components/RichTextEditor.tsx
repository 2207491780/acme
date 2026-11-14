import { useEffect, useRef } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';

/**
 * 富文本编辑器（REQ-20261114-120102，AC-12；契约裁决⑪表面钉死）：
 * 命名导出受控组件 { value, onChange }（对齐 DictSelect 受控惯例），基于 tiptap 最小集
 * （@tiptap/react + starter-kit），供公告正文维护。value/onChange 均为 HTML 字符串——
 * 外部 value 变化（表单回显/重置）同步进编辑器，内部编辑经 onUpdate 向外抛 HTML。
 */

export interface RichTextEditorProps {
  value?: string;
  onChange?: (html: string) => void;
}

export function RichTextEditor({ value, onChange }: RichTextEditorProps) {
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const editor = useEditor({
    extensions: [StarterKit],
    content: value ?? '',
    editorProps: {
      attributes: {
        'aria-label': '公告正文',
        class: 'rich-text-area',
      },
    },
    onUpdate: ({ editor }) => {
      onChangeRef.current?.(editor.getHTML());
    },
  });

  // 外部 value（Form 回显/重置）→ 编辑器内容；与编辑器现值一致时跳过（避免受控循环）
  useEffect(() => {
    if (!editor || editor.isDestroyed) {
      return;
    }
    const next = value ?? '';
    if (next !== editor.getHTML()) {
      editor.commands.setContent(next, { emitUpdate: false });
    }
  }, [editor, value]);

  return <EditorContent editor={editor} className="rich-text-editor" />;
}
