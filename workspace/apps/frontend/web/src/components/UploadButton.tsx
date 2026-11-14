import { useRef, useState, type ChangeEvent, type ReactNode } from 'react';
import { Button } from 'antd';
import type { ButtonProps } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { App as AntApp } from 'antd';
import {
  FILE_ENDPOINTS,
  transferErrorMessage,
  uploadFormData,
  useMeStore,
  type FileView,
} from '@acme/core';

export interface UploadButtonProps {
  /** 上传端点（缺省 /admin/v1/infra/files；业务端点按同范式传 URL）。 */
  url?: string;
  /** 业务归类（随 multipart 可选字段 bizType/bizId 透传）。 */
  bizType?: string;
  bizId?: string;
  /** 按钮文案。 */
  label?: ReactNode;
  /** 按钮类型（缺省 primary）。 */
  buttonType?: ButtonProps['type'];
  /** 文件选择过滤（input accept）。 */
  accept?: string;
  /** 上传权限码（缺省 infra:file:upload；无权限不渲染，fail-closed）。 */
  permission?: string;
  /** 上传成功回调（页面据此刷新列表）。 */
  onUploaded?: (file: FileView) => void;
}

/**
 * 通用上传按钮（AntD5 展示层，REQ-20261114-120101）：
 * 既有 fetch/FormData（无新依赖）；上传在途以按钮 loading 反馈进度；
 * FILE_SIZE_EXCEEDED / FILE_TYPE_REJECTED 等错误码统一转译提示。
 */
export function UploadButton({
  url,
  bizType,
  bizId,
  label = '上传文件',
  buttonType = 'primary',
  accept,
  permission = 'infra:file:upload',
  onUploaded,
}: UploadButtonProps) {
  const { message } = AntApp.useApp();
  const permissions = useMeStore((s) => s.me?.permissions ?? []);
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);

  if (!permissions.includes(permission)) {
    return null;
  }

  const pick = () => {
    inputRef.current?.click();
  };

  const upload = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // 允许重复选择同一文件
    event.target.value = '';
    if (!file) {
      return;
    }
    setUploading(true);
    try {
      const uploaded = await uploadFormData<FileView>(url ?? FILE_ENDPOINTS.base, file, { bizType, bizId });
      message.success(`${uploaded.originalName ?? file.name} 上传成功`);
      onUploaded?.(uploaded);
    } catch (ex: unknown) {
      message.error(transferErrorMessage(ex, '上传失败，请稍后重试'));
    } finally {
      setUploading(false);
    }
  };

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        style={{ display: 'none' }}
        onChange={(e) => void upload(e)}
        aria-hidden="true"
        tabIndex={-1}
      />
      <Button type={buttonType} icon={<UploadOutlined />} loading={uploading} onClick={pick}>
        {label}
      </Button>
    </>
  );
}
