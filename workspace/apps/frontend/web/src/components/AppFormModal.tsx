import { App, Col, Form, Input, Modal, Row, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { CheckOutlined } from '@ant-design/icons';
import type { Health } from '@acme/core';
import { useStore } from '../store/useStore';

const VERSION_RE = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;

interface AppFormValues {
  name: string;
  version: string;
  owner: string;
  health: Health;
  description: string;
}

export function AppFormModal() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const modal = useStore((s) => s.modal);
  const app = useStore((s) => s.state.apps.find((a) => modal?.type === 'app' && a.id === modal.id));
  const dispatch = useStore((s) => s.dispatch);
  const closeModal = useStore((s) => s.closeModal);
  const [form] = Form.useForm<AppFormValues>();
  const open = modal?.type === 'app';

  const initialValues = app
    ? { ...app }
    : { name: '', version: '', owner: '', health: 'healthy', description: '' };

  const onFinish = (values: AppFormValues) => {
    if (modal?.type !== 'app') return;
    dispatch({
      type: 'UPDATE_APP',
      id: modal.id,
      patch: {
        name: values.name.trim(),
        version: values.version.trim(),
        owner: values.owner.trim(),
        health: values.health,
        description: values.description.trim(),
      },
    });
    message.success(t('apps.form.updated'));
    closeModal();
  };

  return (
    <Modal
      title={t('apps.form.title', { name: app?.name || '' })}
      open={open}
      onCancel={closeModal}
      onOk={() => form.submit()}
      okText={t('apps.form.ok')}
      cancelText={t('apps.form.cancel')}
      okButtonProps={{ icon: <CheckOutlined /> }}
      destroyOnHidden
      width={600}
    >
      <Form key={modal?.type === 'app' ? modal.id : 'app'} form={form} layout="vertical" onFinish={onFinish} requiredMark preserve={false} initialValues={initialValues}>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="name" label={t('apps.form.name')} rules={[{ required: true, message: t('apps.form.nameRequired') }]}>
              <Input allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="version"
              label={t('apps.form.version')}
              rules={[
                { required: true, message: t('apps.form.versionRequired') },
                { pattern: VERSION_RE, message: t('apps.form.versionPattern') },
              ]}
            >
              <Input allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="owner" label={t('apps.form.owner')} rules={[{ required: true, message: t('apps.form.ownerRequired') }]}>
              <Input allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="health" label={t('apps.form.health')} rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'healthy', label: t('apps.form.healthOptions.healthy') },
                  { value: 'warning', label: t('apps.form.healthOptions.warning') },
                  { value: 'critical', label: t('apps.form.healthOptions.critical') },
                ]}
              />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item name="description" label={t('apps.form.description')}>
              <Input.TextArea rows={3} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Modal>
  );
}
