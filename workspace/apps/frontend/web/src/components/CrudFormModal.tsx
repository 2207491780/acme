import type { ReactNode } from 'react';
import { Form, Modal } from 'antd';
import type { FormInstance } from 'antd';

export interface CrudFormModalProps<Values extends object> {
  open: boolean;
  title: ReactNode;
  /** AntD Form 实例（页面经 Form.useForm 创建；打开前自行 setFieldsValue/resetFields）。 */
  form: FormInstance<Values>;
  /** 提交中（确定按钮 loading）。 */
  submitting?: boolean;
  onOk: () => void;
  onCancel: () => void;
  /** 表单内容（Form.Item 由页面声明；表单布局统一 vertical）。 */
  children: ReactNode;
  /** 弹窗宽度（AntD 默认 520）。 */
  width?: number;
}

/**
 * 通用 CRUD 表单弹窗（AntD5 展示层）：Modal + 垂直 Form 壳（destroyOnClose + confirmLoading），
 * 字段与校验由页面以 Form.Item 声明；数据面由 @acme/core useCrud / 各域 API 承担。
 */
export function CrudFormModal<Values extends object>({
  open,
  title,
  form,
  submitting = false,
  onOk,
  onCancel,
  children,
  width,
}: CrudFormModalProps<Values>) {
  return (
    <Modal
      open={open}
      title={title}
      onCancel={onCancel}
      onOk={onOk}
      confirmLoading={submitting}
      destroyOnClose
      width={width}
    >
      <Form form={form} layout="vertical">
        {children}
      </Form>
    </Modal>
  );
}
