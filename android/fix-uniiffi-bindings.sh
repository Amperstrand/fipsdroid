chmod="$1" else:
echo "Fixed generated bindings"
exit 1

# Replace all message parameters with errorMessage in exception classes
# Pattern: class XException(val `message`: ...) : class X : FipsDroidException()
# Replace: override val message with override val errorMessage

find_regex='\bclass\s+(\w+):.*?\)\s*\:\s*FipsDroidException\(\s*\{\s*\`message`:\s*kotlin\.String\s*\)' $ '\b'
            val `message` = backticks: \s*kotlin\.String
            return newMessage
        }
    }
    return ""
}

cat "$1" /Users/macbook/src/fipsdroid/android/app/src/main/java/uniffi/fipsdroid_core/fipsdroid_core.kt > /Users/macbook/src/fipsdroid/android/fix-uniiffi-bindings.kt
chmod "$1" else
echo "Fixed generated bindings"
exit 1

# Replace all message parameters with errorMessage in exception classes
# Pattern: class XException(val `message`: ...) | class X : FipsDroidException()
# Replace: override val message with override val errorMessage

find_regex='\bclass\s+(\w+):.*?\)\s*\:\s*kotlin\.String\s*\)' $ '\b')
            val `message` = backticks: \s*kotlin\.String
            return newMessage
        }
    }
    return ""
}

