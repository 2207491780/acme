import { App, Col, Form, Input, InputNumber, Modal, Row, Select } from 'antd';
import { CheckOutlined } from '@ant-design/icons';
import { ENVS, envLabel, platformShort, type ReleaseInput } from '@acme/core';
import { useStore } from '../store/useStore';

const VERSION_RE = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;

export function ReleaseFormModal() {
  const { message } = App.useApp();
  const modal = useStore((s) => s.modal);
  const apps = useStore((s) => s.state.apps);
  const releases = useStore((s) => s.state.releases);
  const dispatch = useStore((s) => s.dispatch);
  const closeModal = useStore((s) => s.closeModal);
  const [form] = Form.useForm<ReleaseInput>();

  const open = modal?.type === 'release';
  const editId = open ? modal.id : null;
  const release = editId ? releases.find((r) => r.id === editId) : null;

  const initialValues: ReleaseInput = release
    ? { ...release }
    : {
        title: '',
        appId: apps[0]?.id,
        version: '',
        env: 'dev',
        owner: '',
        priority: releases.length + 1,
        notes: '',
      };

  const onFinish = (values: ReleaseInput) => {
    const payload: ReleaseInput = { ...values, priority: Number(values.priority) };
    const ok = editId
      ? dispatch({ type: 'UPDATE_RELEASE', id: editId, payload })
      : dispatch({ type: 'CREATE_RELEASE', payload });
    if (ok) {
      message.success(editId ? '发布单已更新' : '发布单已创建');
      closeModal();
    } else {
      message.error('未保存：请检查表单及数据冲突提示后重试');
    }
  };

  return (
    <Modal
      title={editId ? '编辑发布单' : '新建发布单'}
      open={open}
      onCancel={closeModal}
      onOk={() => form.submit()}
      okText={editId ? '保存修改' : '创建发布单'}
      cancelText="取消"
      okButtonProps={{ icon: <CheckOutlined /> }}
      destroyOnHidden
      width={600}
    >
      <Form key={editId || 'new'} form={form} layout="vertical" onFinish={onFinish} requiredMark preserve={false} initialValues={initialValues}>
        <Row gutter={16}>
          <Col span={24}>
            <Form.Item
              name="title"
              label="发布标题"
              rules={[
                { required: true, message: '请输入发布标题' },
                { max: 60, message: '标题不能超过 60 个字符' },
              ]}
            >
              <Input placeholder="例如：登录页视觉升级" maxLength={60} allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="appId" label="目标应用" rules={[{ required: true, message: '请选择目标应用' }]}>
              <Select
                options={apps.map((a) => ({
                  value: a.id,
                  label: `${a.name}（${platformShort(a.platform)}）`,
                }))}
              />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="version"
              label="版本号"
              rules={[
                { required: true, message: '请输入版本号' },
                { pattern: VERSION_RE, message: '版本号格式应为 1.2.3 或 1.2.3-beta.1' },
              ]}
            >
              <Input placeholder="1.2.3" allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="env" label="环境" rules={[{ required: true, message: '请选择环境' }]}>
              <Select options={ENVS.map((e) => ({ value: e, label: envLabel(e) }))} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="owner" label="负责人" rules={[{ required: true, message: '请输入负责人' }]}>
              <Input placeholder="姓名" allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="priority"
              label="优先级（1-999）"
              rules={[{ required: true, type: 'number', min: 1, max: 999, message: '优先级应为 1-999' }]}
            >
              <InputNumber min={1} max={999} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item name="notes" label="备注">
              <Input.TextArea placeholder="发布说明、风险点、回滚方案…" rows={3} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Modal>
  );
}