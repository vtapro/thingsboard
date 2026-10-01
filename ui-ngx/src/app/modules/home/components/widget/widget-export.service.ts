// SPDX-FileCopyrightText: Copyright The Thingsboard Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { Router } from '@angular/router';
import JSZip from 'jszip';
import { WidgetContext } from '@home/models/widget-component.models';
import { DatasourceData, DatasourceType } from '@shared/models/widget.models';
import { AliasFilterType } from '@shared/models/alias.models';

export type WidgetExportFormat = 'csv' | 'xls' | 'xlsx';

interface WidgetExportTable {
  name: string;
  header: string[];
  rows: Array<Array<string | number | boolean>>;
}

/**
 * Export of the data of one widget (the download action of the widget header), like the
 * "Export data to CSV / XLS / XLSX" menu of ThingsBoard PE.
 *
 * <p>The time series of every datasource becomes one wide table (a row per timestamp, a column per key), so the
 * file can be opened directly in Excel for a report; the latest values / attributes of the widget become a second
 * table (Entity | Alias | Key | Value | Unit | Timestamp). XLSX is generated without any extra dependency (JSZip),
 * XLS is an Excel compatible HTML table, because both formats must be offered like in PE.
 */
@Injectable({
  providedIn: 'root'
})
export class WidgetExportService {

  constructor(private router: Router) {}

  public canExport(ctx: WidgetContext): boolean {
    return this.isDashboardRoute()
      && this.hasEntityDatasource(ctx)
      && (this.hasData(ctx?.data) || this.hasData(ctx?.latestData));
  }

  /**
   * The export belongs to the widgets of a dashboard (the pages of the user), not to the home pages
   * (System administrator / Tenant administrator home), which only show counts and platform metrics.
   */
  private isDashboardRoute(): boolean {
    return (this.router.url || '').startsWith('/dashboards');
  }

  /**
   * Only the widgets that read the data of an entity (time series, attributes, latest values) are exportable, like
   * in ThingsBoard PE. The counters (entity/alarm count), the widgets computed by a JavaScript function and the
   * "Api usage state" widgets (CPU/RAM/Disk metrics of the platform) carry no entity data to export.
   */
  private hasEntityDatasource(ctx: WidgetContext): boolean {
    const aliases = ctx?.aliasController?.getEntityAliases?.() || {};
    return !!ctx?.datasources?.some(datasource => {
      if (datasource?.type !== DatasourceType.entity && datasource?.type !== DatasourceType.device) {
        return false;
      }
      const alias = datasource.entityAliasId ? aliases[datasource.entityAliasId] : null;
      return !alias || alias.filter?.type !== AliasFilterType.apiUsageState;
    });
  }

  public exportWidget(ctx: WidgetContext, format: WidgetExportFormat): void {
    const tables = this.buildTables(ctx);
    if (!tables.length) {
      return;
    }
    const fileName = this.fileName(ctx, format);
    switch (format) {
      case 'csv':
        this.download(new Blob(['\ufeff' + this.buildCsv(tables)], {type: 'text/csv;charset=utf-8;'}), fileName);
        break;
      case 'xls':
        this.download(new Blob([this.buildExcelHtml(tables)], {type: 'application/vnd.ms-excel'}), fileName);
        break;
      case 'xlsx':
        // JSZip generates asynchronously (the synchronous API was removed in JSZip 3)
        this.buildXlsx(tables).then(blob => this.download(blob, fileName));
        break;
    }
  }

  private hasData(data?: DatasourceData[]): boolean {
    return !!data?.some(entry => entry?.data?.length);
  }

  private buildTables(ctx: WidgetContext): WidgetExportTable[] {
    const tables: WidgetExportTable[] = [];
    const series = this.buildSeriesTable(ctx?.data || []);
    if (series) {
      tables.push(series);
    }
    const latest = this.buildLatestTable(ctx?.latestData || []);
    if (latest) {
      tables.push(latest);
    }
    return tables;
  }

  /** A row per timestamp, a column per (entity · key): the classic chart data table. */
  private buildSeriesTable(data: DatasourceData[]): WidgetExportTable | null {
    const columns: string[] = [];
    const values = new Map<number, Map<string, any>>();
    data.forEach(datasourceData => {
      if (!datasourceData?.data?.length) {
        return;
      }
      const column = this.columnLabel(datasourceData);
      if (!columns.includes(column)) {
        columns.push(column);
      }
      datasourceData.data.forEach(entry => {
        const ts = entry[0];
        let row = values.get(ts);
        if (!row) {
          row = new Map<string, any>();
          values.set(ts, row);
        }
        row.set(column, entry[1]);
      });
    });
    if (!columns.length) {
      return null;
    }
    const timestamps = Array.from(values.keys()).sort((a, b) => a - b);
    const rows = timestamps.map(ts => {
      const row = values.get(ts);
      return [this.formatTs(ts), ...columns.map(column => row.has(column) ? row.get(column) : '')];
    });
    return {name: 'Time series', header: ['Timestamp', ...columns], rows};
  }

  /** One row per key: the latest values and the attributes of the widget. */
  private buildLatestTable(data: DatasourceData[]): WidgetExportTable | null {
    const rows: Array<Array<string | number | boolean>> = [];
    data.forEach(datasourceData => {
      if (!datasourceData?.data?.length) {
        return;
      }
      const datasource = datasourceData.datasource;
      const dataKey = datasourceData.dataKey;
      const entity = datasource?.entityName || datasource?.deviceId || datasource?.entityId || '';
      const alias = datasource?.aliasName || '';
      const key = dataKey?.label || dataKey?.name || '';
      const unit = dataKey?.units || '';
      datasourceData.data.forEach(entry => {
        rows.push([entity, alias, key, entry[1] as any, unit, this.formatTs(entry[0])]);
      });
    });
    if (!rows.length) {
      return null;
    }
    return {
      name: 'Latest values',
      header: ['Entity', 'Alias', 'Key', 'Value', 'Unit', 'Timestamp'],
      rows
    };
  }

  private columnLabel(datasourceData: DatasourceData): string {
    const entity = datasourceData.datasource?.entityName
      || datasourceData.datasource?.deviceId
      || datasourceData.datasource?.entityId
      || datasourceData.datasource?.aliasName
      || 'entity';
    const key = datasourceData.dataKey?.label || datasourceData.dataKey?.name || 'value';
    const unit = datasourceData.dataKey?.units;
    return unit ? `${entity} · ${key} (${unit})` : `${entity} · ${key}`;
  }

  private formatTs(ts: number): string {
    const date = new Date(ts);
    const pad = (value: number, size = 2) => String(value).padStart(size, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
      + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
  }

  private buildCsv(tables: WidgetExportTable[]): string {
    const lines: string[] = [];
    tables.forEach((table, index) => {
      if (index > 0) {
        lines.push('');
      }
      lines.push(this.csvRow([table.name]));
      lines.push(this.csvRow(table.header));
      table.rows.forEach(row => lines.push(this.csvRow(row)));
    });
    return lines.join('\r\n');
  }

  private csvRow(row: Array<string | number | boolean>): string {
    return row.map(value => {
      if (value === null || value === undefined) {
        return '';
      }
      const text = String(value);
      return /[",\r\n;]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
    }).join(',');
  }

  /** Excel opens an HTML table saved with the .xls extension, which is how this format is offered in PE too. */
  private buildExcelHtml(tables: WidgetExportTable[]): string {
    const escape = (value: any) => String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const tablesHtml = tables.map(table => {
      const head = '<tr>' + table.header.map(cell => `<th>${escape(cell)}</th>`).join('') + '</tr>';
      const body = table.rows.map(row => '<tr>' + row.map(cell => `<td>${escape(cell)}</td>`).join('') + '</tr>').join('');
      return `<h3>${escape(table.name)}</h3><table border="1">${head}${body}</table>`;
    }).join('<br/>');
    return `<html xmlns:x="urn:schemas-microsoft-com:office:excel"><head><meta charset="utf-8"/></head>`
      + `<body>${tablesHtml}</body></html>`;
  }

  private buildXlsx(tables: WidgetExportTable[]): Promise<Blob> {
    const zip = new JSZip();
    zip.file('[Content_Types].xml',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      + '<Default Extension="xml" ContentType="application/xml"/>'
      + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
      + tables.map((table, index) =>
        `<Override PartName="/xl/worksheets/sheet${index + 1}.xml" `
        + 'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>').join('')
      + '</Types>');
    zip.file('_rels/.rels',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
      + '</Relationships>');
    zip.file('xl/workbook.xml',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
      + 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
      + tables.map((table, index) =>
        `<sheet name="${this.sheetName(table.name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>`).join('')
      + '</sheets></workbook>');
    zip.file('xl/_rels/workbook.xml.rels',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
      + tables.map((table, index) =>
        `<Relationship Id="rId${index + 1}" `
        + 'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
        + `Target="worksheets/sheet${index + 1}.xml"/>`).join('')
      + '</Relationships>');
    tables.forEach((table, index) => {
      zip.file(`xl/worksheets/sheet${index + 1}.xml`, this.sheetXml(table));
    });
    return zip.generateAsync({type: 'blob',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'});
  }

  private sheetXml(table: WidgetExportTable): string {
    const rows = [table.header, ...table.rows].map((row, rowIndex) => {
      const cells = row.map((value, columnIndex) =>
        this.cellXml(value, `${this.columnRef(columnIndex)}${rowIndex + 1}`)).join('');
      return `<row r="${rowIndex + 1}">${cells}</row>`;
    }).join('');
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
      + `<sheetData>${rows}</sheetData></worksheet>`;
  }

  private cellXml(value: string | number | boolean, ref: string): string {
    if (value === null || value === undefined || value === '') {
      return `<c r="${ref}"/>`;
    }
    if (typeof value === 'number' && isFinite(value)) {
      return `<c r="${ref}"><v>${value}</v></c>`;
    }
    if (typeof value === 'boolean') {
      return `<c r="${ref}" t="b"><v>${value ? 1 : 0}</v></c>`;
    }
    const text = String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return `<c r="${ref}" t="inlineStr"><is><t xml:space="preserve">${text}</t></is></c>`;
  }

  private columnRef(index: number): string {
    let ref = '';
    let value = index;
    do {
      ref = String.fromCharCode(65 + (value % 26)) + ref;
      value = Math.floor(value / 26) - 1;
    } while (value >= 0);
    return ref;
  }

  private sheetName(name: string): string {
    return name.replace(/[\\/?*[\]:]/g, ' ').slice(0, 31);
  }

  private fileName(ctx: WidgetContext, format: WidgetExportFormat): string {
    const dashboard = (ctx?.dashboard as any)?.dashboard?.title
      || (ctx?.dashboard as any)?.parentDashboard?.dashboard?.title
      || (ctx?.parentDashboard as any)?.dashboard?.title
      || '';
    const widget = ctx?.widgetTitle || ctx?.widgetConfig?.title || 'widget';
    const stamp = this.formatTs(Date.now()).replace(/[-: ]/g, '').replace('.', '');
    const safe = (value: string) => value.replace(/[\\/:*?"<>|]/g, '-').slice(0, 60);
    return `${safe([dashboard, widget].filter(value => !!value).join(' - '))} - ${stamp}.${format}`;
  }

  private download(blob: Blob, fileName: string): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

}
