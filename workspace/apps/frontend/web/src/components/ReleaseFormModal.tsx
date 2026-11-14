import { App, Col, Form, Input, InputNumber, Modal, Row, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import { CheckOutlined } from '@ant-design/icons';
import { ENVS, type ReleaseInput } from '@acme/core';
import { envLabelKey } from '../labels';
import { useStore } from '../store/useStore';

const VERSION_RE = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;

export function ReleaseFormModal() {
  const { t } = useTranslation();
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
      message.success(editId ? t('releases.form.updated') : t('releases.form.created'));
      closeModal();
    } else {
      message.error(t('releases.form.conflict'));
    }
  };

  return (
    <Modal
      title={editId ? t('releases.form.editTitle') : t('releases.form.createTitle')}
      open={open}
      onCancel={closeModal}
      onOk={() => form.submit()}
      okText={editId ? t('releases.form.okEdit') : t('releases.form.okCreate')}
      cancelText={t('releases.form.cancel')}
      okButtonProps={{ icon: <CheckOutlined /> }}
      destroyOnHidden
      width={600}
    >
      <Form key={editId || 'new'} form={form} layout="vertical" onFinish={onFinish} requiredMark preserve={false} initialValues={initialValues}>
        <Row gutter={16}>
          <Col span={24}>
            <Form.Item
              name="title"
              label={t('releases.form.title')}
              rules={[
                { required: true, message: t('releases.form.titleRequired') },
                { max: 60, message: t('releases.form.titleMax') },
              ]}
            >
              <Input placeholder={t('releases.form.titlePlaceholder')} maxLength={60} allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="appId" label={t('releases.form.app')} rules={[{ required: true, message: t('releases.form.appRequired') }]}>
              <Select
                options={apps.map((a) => ({
                  value: a.id,
                  label: `${a.name}（${t(`releases.platform.${a.platform}`)}）`,
                }))}
              />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="version"
              label={t('releases.form.version')}
              rules={[
                { required: true, message: t('releases.form.versionRequired') },
                { pattern: VERSION_RE, message: t('releases.form.versionPattern') },
              ]}
            >
              <Input placeholder="1.2.3" allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="env" label={t('releases.form.env')} rules={[{ required: true, message: t('releases.form.envRequired') }]}>
              <Select options={ENVS.map((e) => ({ value: e, label: t(envLabelKey(e)) }))} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="owner" label={t('releases.form.owner')} rules={[{ required: true, message: t('releases.form.ownerRequired') }]}>
              <Input placeholder={t('releases.form.ownerPlaceholder')} allowClear />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item
              name="priority"
              label={t('releases.form.priority')}
              rules={[{ required: true, type: 'number', min: 1, max: 999, message: t('releases.form.priorityInvalid') }]}
            >
              <InputNumber min={1} max={999} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Form.Item name="notes" label={t('releases.form.notes')}>
              <Input.TextArea placeholder={t('releases.form.notesPlaceholder')} rows={3} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Modal>
  );
}
