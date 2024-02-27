curId = 0
method = "GET"
headers = {}
headers["Host"] = "http://localhost:8088"
endpoint = "/v0/entity?id="

function request()
    curKey = "key" .. curId
    curId = curId + 1
    return wrk.format(method,  endpoint .. curKey, headers)
end
