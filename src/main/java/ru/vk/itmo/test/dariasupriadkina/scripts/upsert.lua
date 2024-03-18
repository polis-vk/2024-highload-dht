math.randomseed(os.time())

headers = {}
headers["Host"] = "localhost:8080"

function strand(length)
    local res = ""
    for _ = 1, length do
        res = res .. string.char(math.random(97, 122))
    end
    return res
end


request = function()
    length = math.random(3, 100)
    id = math.random(1, 100000000)
    return wrk.format("PUT", "/v0/entity?id=" .. id, headers, strand(length))
end