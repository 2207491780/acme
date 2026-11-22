import { useRef, type ChangeEvent, type ReactElement } from 'react';
import { Button, Form, Input, Modal, Typography } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { FONT_FAMILY } from '@acme/core';

/**
 * 导入弹层（REQ-20261122-023441 AC-7）：粘贴 XML / 选择 .bpmn 文件 →
 * 解析交由父级（parseBpmnXml 失败错误反馈不入画布）；本组件只承载取数与提交。
 */

const { Text } = Typography;

export interface ImportXmlModalProps {
  open: boolean;
  onClose: () => void;
  onImport: (xml: string) => void;
}

export function ImportXmlModal({ open, onClose, onImport }: ImportXmlModalProps): ReactElement {
  const { t } = useTranslation();
  const [form] = Form.useForm<{ xml: string }>();
  const fileRef = useRef<HTMLInputElement | null>(null);

  const readSelectedFile = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    void file.text().then((content) => form.setFieldValue('xml', content));
  };

  const submit = () => {
    const xml = (form.getFieldValue('xml') as string | undefined)?.trim() ?? '';
    if (xml === '') return;
    onImport(xml);
    form.resetFields();
  };

  return (
    <Modal
      open={open}
      title={t('workflow.designer.import.title')}
      onCancel={onClose}
      onOk={submit}
      okText={t('workflow.designer.import.submit')}
      cancelText={t('workflow.designer.import.cancel')}
      destroyOnClose
    >
      <Form form={form} layout="vertical">
        <Form.Item label={t('workflow.designer.import.pasteLabel')} name="xml">
          <Input.TextArea
            rows={10}
            style={{ fontFamily: FONT_FAMILY.mono, fontSize: 12 }}
            data-testid="import-xml-textarea"
          />
        </Form.Item>
        <Form.Item label={t('workflow.designer.import.fileLabel')}>
          <Button icon={<UploadOutlined />} onClick={() => fileRef.current?.click()} data-testid="import-xml-file-button">
            <input ref={fileRef} type="file" accept=".bpmn,.xml,text/xml,application/xml" hidden onChange={readSelectedFile} data-testid="import-xml-file-input" />
            {t('workflow.designer.import.fileLabel')}
          </Button>
        </Form.Item>
      </Form>
      <Text type="secondary" style={{ fontSize: 12 }}>BPMN 2.0 + acme: 扩展（http://acme.dev/bpmn/ext/v1）</Text>
    </Modal>
  );
}
