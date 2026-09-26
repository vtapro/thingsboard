# Blueprint: tách tab Roles thành 3 cột

Áp dụng cho `ui-ngx/src/app/modules/home/pages/admin/roles.component.html` + `.scss`.
Mục tiêu: hết cảnh cuộn dài, mỗi cột một nhóm chức năng, chỉ còn **1 nút Save**.

Trạng thái: **đã triển khai**. Trang Roles dùng hết bề ngang và các cột auto-fit theo màn hình.

## 1. SCSS (thêm vào roles.component.scss)

```scss
.tb-roles-3col {
  display: grid;
  gap: 16px;
  align-items: start;
  /* auto-fit: 3 cột khi rộng, 2 cột khi vừa, 1 cột khi hẹp; các cột luôn lấp đầy bề ngang */
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr));
}

/* Trang Roles dùng hết bề ngang: bỏ max-width 1440px và override rule width 60%/80% của settings-card.scss */
.tb-roles-page {
  padding: 16px;
  width: 100%;
  max-width: none;
  margin: 0;
}

.tb-roles-page .mat-mdc-card.settings-card.tb-roles-card {
  width: 100%;
  margin: 8px 0;
}

.tb-roles-col {
  min-width: 0;   /* để mat-form-field không tràn cột */
}

.tb-roles-role-list {
  max-height: 320px;
  overflow: auto;

  .tb-roles-role-row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px;
    border-radius: 6px;
    cursor: pointer;

    &:hover { background: rgba(0, 0, 0, .04); }
    &.tb-selected { background: rgba(25, 118, 210, .12); }
  }
}

/* ma trận quyền 2 cột cho gọn */
.tb-roles-ops {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 16px;
}
```

## 2. HTML — bọc 3 cột trong tab Roles

```html
<div class="tb-roles-3col">
  <!-- CỘT 1: thông tin role + danh sách role -->
  <mat-card appearance="outlined" class="tb-roles-col tb-roles-col-1">
    <mat-card-content>
      <div class="mat-subtitle-1">{{ 'admin.roles-create' | translate }}</div>
      <mat-form-field class="mat-block" subscriptSizing="dynamic" appearance="outline">
        <mat-label translate>admin.roles-name</mat-label>
        <input matInput [formControl]="nameControl">
      </mat-form-field>
      <mat-checkbox [formControl]="ownCustomerOnlyControl" translate>admin.roles-own-customer</mat-checkbox>
      <div class="tb-roles-inline-actions">
        <button mat-raised-button color="primary" (click)="saveRole()"
                [disabled]="nameControl.invalid">
          {{ (isEditingRole ? 'action.save' : 'action.add') | translate }}
        </button>
        @if (isEditingRole) {
          <button mat-button (click)="cancelEditRole()" translate>action.cancel</button>
        }
      </div>

      <mat-divider></mat-divider>
      <div class="mat-subtitle-1">{{ 'admin.roles-tenant-roles' | translate }}</div>
      <div class="tb-roles-role-list">
        @for (role of roles; track role.id) {
          <div class="tb-roles-role-row" [class.tb-selected]="role.id === editingRoleId"
               (click)="editRole(role)">
            <span class="tb-roles-role-name">{{ role.name }}</span>
            @for (chip of permissionChips(role); track chip.label) {
              <span class="tb-roles-chip">{{ chip.label }}</span>
            }
          </div>
        }
      </div>
    </mat-card-content>
  </mat-card>

  <!-- CỘT 2: ma trận quyền (giữ nguyên tabs entity type + presets + checkbox + scope) -->
  <mat-card appearance="outlined" class="tb-roles-col tb-roles-col-2">
    <!-- nội dung card "Create a role" hiện tại, bỏ 2 input Name/Own customer only đã chuyển sang cột 1 -->
  </mat-card>

  <!-- CỘT 3: users + hành động -->
  <mat-card appearance="outlined" class="tb-roles-col tb-roles-col-3">
    <mat-card-content>
      <div class="mat-subtitle-1">{{ 'admin.roles-users' | translate }}</div>
      <!-- dropdown users của role đang chọn -->
      <div class="tb-roles-inline-actions">
        <button mat-raised-button color="primary" (click)="saveRoles()"
                [disabled]="!roles.length" translate>action.save</button>
      </div>
    </mat-card-content>
  </mat-card>
</div>
```

## 3. TS cần thêm

- `get editingRoleId()` — hiện là `private`, đổi thành public getter để template so sánh `role.id === editingRoleId`.
- `saveRole()` — tách phần “build role từ draft + thêm/thay thế trong `this.roles`” ra khỏi `save()` hiện tại
  (giữ nguyên logic đã sửa: **thay thế** entry cũ, giữ `userIds` của role đang sửa).
- `saveRoles()` — giữ nguyên (POST `/api/tenant/role {roles}`) nhưng **bỏ nút riêng** ở panel “Tenant roles” cũ;
  nút Save duy nhất nằm ở cột 3 (hoặc gọi `saveRoles()` ngay sau `saveRole()` để chỉ còn 1 thao tác).
- i18n cần thêm: `admin.roles-create`, `admin.roles-name`, `admin.roles-own-customer`,
  `admin.roles-tenant-roles`, `admin.roles-users`.

## 4. Kiểm tra sau khi đổi

1. `ng build --configuration production` phải pass.
2. Mở Roles: 3 cột hiện đủ ở màn ≥1200px; thu nhỏ xuống 2 rồi 1 cột đúng breakpoint.
3. Thêm role mới → xuất hiện ngay trong danh sách cột 1; sửa role → badge quyền đổi sau khi Save.
4. Tạo thiết bị bằng `user@thingsboard.org` → bằng user khác **không thấy** trong danh sách; mở trực tiếp → **403**.
