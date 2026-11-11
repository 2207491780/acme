import { App, Col, Form, Input, Modal, Row, Select } from 'antd';
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
    message.success('应用信息已更新');
    closeModal();
  };

  return (
    <Modal
      title={`编辑应用 · ${app?.name || ''}`}
      open={open}
      onCancel={closeModal}
      onOk={() => form.submit()}
      okText="保存"
      cancelText="取消"
      okButtonProps={{ icon: <CheckOutlined /> }}
      destroyOnHidden
      width={600}
    >
      <Form key={modal?.type === 'app' ? modal.id : 'app'} form={form} layout="vertical" onFinish={onFinish} requiredMark preserve={false} initialValues={initialValues}>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="name" label="应用名称" rules={[{ required: true, message: '请输入应用名称' }]}>
              <Input allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="version"
              label="当前版本"
              rules={[
                { required: true, message: '请输入版本号' },
                { pattern: VERSION_RE, message: '版本号格式应为 1.2.3' },
              ]}
            >
              <Input allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="owner" label="负责人" rules={[{ required: true, message: '请输入负责人' }]}>
              <Input allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="health" label="健康度" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'healthy', label: '健康' },
                  { value: 'warning', label: '关注' },
                  { value: 'critical', label: '异常' },
                ]}
              />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item name="description" label="描述">
              <Input.TextArea rows={3} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Modal>
  );
}