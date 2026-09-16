import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 60000
})

// 统一处理 { code, message, data } 结构，业务层只关心 data
http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      return Promise.reject(new Error(body.message || '接口返回异常'))
    }
    return body
  },
  (error) => {
    const msg = error.response
      ? `请求失败（HTTP ${error.response.status}）`
      : '无法连接后端服务，请确认后端已启动'
    return Promise.reject(new Error(msg))
  }
)

/** 把筛选条件转成查询参数，空值不传递 */
function toParams(query) {
  const params = {}
  Object.keys(query || {}).forEach((k) => {
    const v = query[k]
    if (v !== null && v !== undefined && String(v).trim() !== '') {
      params[k] = v
    }
  })
  return params
}

export const api = {
  overview: (q) => http.get('/overview', { params: toParams(q) }),
  trend: (q) => http.get('/trend', { params: toParams(q) }),
  categoryTopN: (q) => http.get('/category/topn', { params: toParams(q) }),
  productTopN: (q) => http.get('/product/topn', { params: toParams(q) }),
  channelDist: (q) => http.get('/channel/dist', { params: toParams(q) }),
  paytypeDist: (q) => http.get('/paytype/dist', { params: toParams(q) }),
  regionTopN: (q) => http.get('/region/topn', { params: toParams(q) }),
  channelCategory: (q) => http.get('/channel-category', { params: toParams(q) }),
  filters: () => http.get('/meta/filters'),

  orderPage: (q) => http.get('/order/page', { params: toParams(q) }),
  orderExportUrl: (q) => {
    const usp = new URLSearchParams(toParams(q))
    return `/api/order/export?${usp.toString()}`
  },

  quality: () => http.get('/monitor/quality'),
  jobLog: () => http.get('/monitor/job-log'),
  consistency: () => http.get('/monitor/consistency')
}

export default api
