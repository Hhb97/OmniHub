import { http } from './request'
import type {
  Device,
  PageQuery,
  PageResult,
  Product,
  ProductForm,
  ProductOption,
  ProductQuery,
  ThingModel,
  ThingModelBody,
  ThingModelVersion,
} from '@/types'

export const productApi = {
  list: (params: PageQuery & ProductQuery) => http.get<PageResult<Product>>('/products', params),
  get: (id: number) => http.get<Product>(`/products/${id}`),
  create: (body: ProductForm) => http.post<Product>('/products', body),
  update: (id: number, body: Partial<ProductForm>) => http.put<Product>(`/products/${id}`, body),
  remove: (id: number) => http.delete<void>(`/products/${id}`),
  devices: (id: number, params: PageQuery) =>
    http.get<PageResult<Device>>(`/products/${id}/devices`, params),
  options: () => http.get<ProductOption[]>('/products/options'),

  getThingModel: (id: number, silent = false) =>
    http.get<ThingModel>(`/products/${id}/thing-model`, undefined, { silent }),
  saveThingModel: (id: number, body: ThingModelBody) =>
    http.put<ThingModel>(`/products/${id}/thing-model`, body),
  thingModelVersions: (id: number) =>
    http.get<ThingModelVersion[]>(`/products/${id}/thing-model/versions`),
}
