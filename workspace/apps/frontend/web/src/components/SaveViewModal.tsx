import { useEffect } from 'react';
import { App, Form, Input, Modal, Typography } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useStore } from '../store/useStore';

export function SaveViewModal() {
  const { message } = App.useApp();
  const modal = useStore((s) => s.modal);
  const query = useStore((s) => s.query);
  const dispatch = useStore((s) => s.dispatch);
  const closeModal = useStore((s) => s.closeModal);
  const [form] = Form.useForm<{ name: string }>();
  const open = modal?.type === 'savedView';

  useEffect(() => {
    if (open) form.setFieldsValue({ name: '' });
  }, [open, form]);

  const onFinish = ({ name }: { name: string }) => {
    const trimmed = name.trim();
    dispatch({ type: 'SAVE_VIEW', name: trimmed, query });
    message.success(`视图「${trimmed}」已保存`);
    closeModal();
  };

  return (
    <Modal
      title="保存当前视图"
      open={open}
      onCancel={closeModal}
      onOk={() => form.submit()}
      okText="保存"
      cancelText="取消"
      okButtonProps={{ icon: <SaveOutlined /> }}
      destroyOnHidden
      width={420}
    >
      <Form form={form} layout="vertical" onFinish={onFinish} preserve={false}>
        <Form.Item
          name="name"
          label="视图名称"
          rules={[
            { required: true, message: '请输入视图名称' },
            { max: 20, message: '名称不能超过 20 个字符' },
          ]}
        >
          <Input placeholder="例如：生产待发布" maxLength={20} allowClear />
        </Form.Item>
        <Typography.Text type="secondary">将保存当前搜索、平台、状态、环境与排序条件。</Typography.Text>
      </Form>
    </Modal>
  );
}