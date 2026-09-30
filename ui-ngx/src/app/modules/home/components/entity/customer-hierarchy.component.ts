// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Store } from '@ngrx/store';
import { PageComponent } from '@shared/components/page.component';
import { AppState } from '@core/core.state';
import { getCurrentAuthState } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { defaultHttpOptionsFromConfig } from '@core/http/http-utils';

interface CustomerInfo {
  id: { id: string };
  title: string;
}

interface HierarchyNode {
  id: string;
  label: string;
  icon: string;
  level: number;
  kind: 'all' | 'customer' | 'group';
  entityType?: string;
  expandable: boolean;
  translatable: boolean;
  parentIds: string[];
}

/** Group types shown under every customer, like the tree of ThingsBoard PE. */
const GROUP_TYPES: Array<{entityType: string; label: string; icon: string}> = [
  {entityType: 'USER', label: 'customer.group-user', icon: 'account_circle'},
  {entityType: 'CUSTOMER', label: 'customer.group-customer', icon: 'group'},
  {entityType: 'ASSET', label: 'customer.group-asset', icon: 'domain'},
  {entityType: 'DEVICE', label: 'customer.group-device', icon: 'devices_other'},
  {entityType: 'ENTITY_VIEW', label: 'customer.group-entity-view', icon: 'view_quilt'}
];

const MAX_CUSTOMER_PAGES = 50;

/**
 * The "Hierarchy" tab of the Customers page (ThingsBoard PE layout): the customer tree on the left, the groups of
 * the selected node on the right. Every group table always contains the "All" group created by the backend.
 */
@Component({
    selector: 'tb-customer-hierarchy',
    templateUrl: './customer-hierarchy.component.html',
    styleUrls: ['./customer-hierarchy.component.scss'],
    standalone: false
})
export class CustomerHierarchyComponent extends PageComponent implements OnInit {

  nodes: HierarchyNode[] = [];
  readonly expanded = new Set<string>();
  selected: HierarchyNode = null;

  constructor(protected store: Store<AppState>,
              private http: HttpClient) {
    super(store);
  }

  ngOnInit(): void {
    if (getCurrentAuthState(this.store).authUser?.authority !== Authority.TENANT_ADMIN) {
      return;
    }
    this.http.get<{parents: {[childId: string]: string}}>('/api/tenant/customerHierarchy',
      defaultHttpOptionsFromConfig(undefined)).subscribe(hierarchy => {
      this.loadCustomers(0, [], hierarchy?.parents || {});
    });
  }

  private loadCustomers(page: number, customers: CustomerInfo[], parents: {[childId: string]: string}): void {
    this.http.get<{data: CustomerInfo[]; hasNext: boolean}>(`/api/customers?pageSize=100&page=${page}`,
      defaultHttpOptionsFromConfig(undefined)).subscribe(data => {
      const loaded = customers.concat(data?.data || []);
      if (data?.hasNext && page + 1 < MAX_CUSTOMER_PAGES) {
        this.loadCustomers(page + 1, loaded, parents);
      } else {
        this.buildTree(loaded, parents);
      }
    });
  }

  private buildTree(customers: CustomerInfo[], parents: {[childId: string]: string}): void {
    const nodes: HierarchyNode[] = [
      {id: 'all', label: 'entity-group.all', icon: 'people', level: 0, kind: 'all',
        expandable: customers.length > 0, translatable: true, parentIds: []}
    ];
    this.expanded.add('all');
    const byId = new Map(customers.map(customer => [customer.id.id, customer]));
    const walk = (parentId: string | null, parentIds: string[], level: number) => {
      for (const customer of customers) {
        const parent = parents[customer.id.id];
        const isChild = parentId === null ? (!parent || !byId.has(parent)) : parent === parentId;
        if (!isChild || nodes.some(node => node.id === 'c:' + customer.id.id)) {
          continue;
        }
        const nodeId = 'c:' + customer.id.id;
        const path = [...parentIds, nodeId];
        nodes.push({id: nodeId, label: customer.title, icon: 'supervisor_account', level, kind: 'customer',
          expandable: true, translatable: false, parentIds});
        for (const groupType of GROUP_TYPES) {
          nodes.push({
            id: 'g:' + customer.id.id + ':' + groupType.entityType,
            label: groupType.label,
            icon: groupType.icon,
            level: level + 1,
            kind: 'group',
            entityType: groupType.entityType,
            expandable: false,
            translatable: true,
            parentIds: path
          });
        }
        walk(customer.id.id, path, level + 1);
      }
    };
    walk(null, ['all'], 1);
    this.nodes = nodes;
  }

  get visibleNodes(): HierarchyNode[] {
    return this.nodes.filter(node => node.parentIds.every(id => this.expanded.has(id)));
  }

  isExpanded(node: HierarchyNode): boolean {
    return this.expanded.has(node.id);
  }

  toggle(node: HierarchyNode, event: Event): void {
    event.stopPropagation();
    if (this.expanded.has(node.id)) {
      this.expanded.delete(node.id);
    } else {
      this.expanded.add(node.id);
    }
  }

  select(node: HierarchyNode): void {
    this.selected = node;
    if (node.expandable) {
      this.expanded.add(node.id);
    }
  }

}
