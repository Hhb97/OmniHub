export type ProductStatus = 'ENABLED' | 'DISABLED'

export interface Product {
  id: number
  name: string
  productKey: string
  category?: string
  protocol: string
  status: ProductStatus
  description?: string
  deviceCount: number
  thingModelVersion?: number
  createdAt: string
  updatedAt?: string
}

export interface ProductForm {
  name: string
  productKey: string
  category: string
  protocol: string
  status: ProductStatus
  description: string
}

export interface ProductQuery {
  keyword?: string
  category?: string
  status?: ProductStatus | ''
}

export interface ProductOption {
  id: number
  name: string
  productKey: string
}
