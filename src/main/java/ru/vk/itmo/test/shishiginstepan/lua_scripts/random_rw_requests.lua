base_url = "/v0/entity?"
headers = {}
headers["Host"] = "localhost:8080"
wrk.port = 8080
function build_query_param(param, value)
    return param .. "=" .. value
end

function generateKey()
    return math.random(0, 100000)
end

function generateValue()
    key_part = math.random(10000000000, 25000000000)
    key = ""
    for i = 0, 20, 1 do
        key = key .. key_part
    end
    return key
end

function getOneRandom()
    return wrk.format("GET", base_url .. build_query_param("id", generateKey()), headers)
end

function putOneRandom()
    return wrk.format("PUT", base_url .. build_query_param("id", generateKey()), headers, generateValue())
end

function deleteOneRandom()
    return wrk.format("DELETE", base_url .. build_query_param("id", generateKey()), headers)
end

request = function()
    --    magic_selector = math.random(0,3)
    --    if magic_selector < 2 then
    --  return getOneRandom()
    --    elseif magic_selector == 3 then
      return putOneRandom()
    --    else
    --        return deleteOneRandom()
    --    end
end

--при тестировании нужные методы просто раскоментировал, хотя и в полной имплементации тоже тестировал
