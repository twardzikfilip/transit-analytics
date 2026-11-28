import { Component, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { t } from '../../core/i18n/t';

/** Asks before a user is deleted; closes with true to delete. */
@Component({
  selector: 'app-delete-user-dialog',
  template: `
    <div class="dialog">
      <h2>{{ t('users.deleteTitle') }}</h2>
      <p>{{ t('users.deleteQuestion', { name: data.username }) }}</p>
      <div class="actions">
        <button type="button" (click)="ref.close(false)">{{ t('users.cancel') }}</button>
        <button type="button" class="primary danger-fill" (click)="ref.close(true)">
          {{ t('users.delete') }}
        </button>
      </div>
    </div>
  `,
  styleUrl: './dialog.scss',
})
export class DeleteUserDialog {
  protected readonly t = t;
  protected readonly ref = inject<MatDialogRef<DeleteUserDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<{ username: string }>(MAT_DIALOG_DATA);
}
