import { useEffect } from 'react';
import { App, Form, Input, Modal, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { SaveOutlined } from '@ant-design/icons';
import { useStore } from '../store/useStore';

export function SaveViewModal() {
  const { t } = useTranslation();
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
    message.success(t('releases.saveView.saved', { name: trimmed }));
    closeModal();
  };

  return (
    <Modal
      title={t('releases.saveView.title')}
      open={open}
      onCancel={closeModal}
      onOk={() => form.submit()}
      okText={t('releases.saveView.ok')}
      cancelText={t('releases.saveView.cancel')}
      okButtonProps={{ icon: <SaveOutlined /> }}
      destroyOnHidden
      width={420}
    >
      <Form form={form} layout="vertical" onFinish={onFinish} preserve={false}>
        <Form.Item
          name="name"
          label={t('releases.saveView.name')}
          rules={[
            { required: true, message: t('releases.saveView.nameRequired') },
            { max: 20, message: t('releases.saveView.nameMax') },
          ]}
        >
          <Input placeholder={t('releases.saveView.namePlaceholder')} maxLength={20} allowClear />
        </Form.Item>
        <Typography.Text type="secondary">{t('releases.saveView.desc')}</Typography.Text>
      </Form>
    </Modal>
  );
}
