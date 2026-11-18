/** en-US shared components resource pack: error boundary / dict select / notification center. */
export const components = {
  errorBoundary: {
    title: 'Something went wrong on this page',
    desc: 'The page failed to render. Try again; if the problem persists, refresh the page.',
    appTitle: 'Unexpected application error',
    appDesc: 'An uncaught error occurred. Refresh the page to recover; contact the administrator if it persists.',
    detail: 'Error details',
    retry: 'Retry',
    reload: 'Refresh page',
  },
  dictSelect: {
    retry: 'Retry',
  },
  lockScreen: {
    title: 'Screen locked',
    desc: 'Enter your lock passcode to continue',
    placeholder: 'Enter lock passcode',
    unlock: 'Unlock',
    unlockFailed: 'Unlock failed, please try again',
    forgot: 'Forgot passcode? Sign in again',
    setDesc: 'First lock: set a passcode to unlock',
    setPlaceholder: 'Enter new lock passcode',
    confirmPlaceholder: 'Re-enter lock passcode',
    set: 'Set passcode',
    setEmpty: 'Passcode cannot be empty',
    setMismatch: 'Passcodes do not match',
  },
  notification: {
    center: 'Notification center',
    markAllRead: 'Mark all read',
    read: 'Read',
    unread: 'Unread',
    inboxLoadFailed: 'Failed to load the inbox',
    markReadFailed: 'Failed to mark as read, please try again later',
    markAllReadFailed: 'Failed to mark all as read, please try again later',
  },
};
