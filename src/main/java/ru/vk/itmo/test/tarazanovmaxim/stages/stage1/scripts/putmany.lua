wrk.method = "PUT"

key = 0

request = function()
    key = key + 1
    path = "/v0/entity?id=" .. key
    body = "hello world" .. key
    headers = {}
    headers["Content-Type"] = "text/plain"
    return wrk.format(nil, path, headers, body)
end