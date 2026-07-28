#include "app_list.h"
#include <stdbool.h>
#include <stdint.h>

/*
 *@brief 链表节点内存池分配函数
 *@param List 链表句柄指针
 */
static ListNode_t *AllocateNode(ListHandle_t *List)
{
    for (int i = 0; i < List->PoolSize; i++)
    {
        if (List->NodePool[i].IsUsed == false)
        {
            List->NodePool[i].IsUsed = true;
            return &List->NodePool[i];
        }
    }
    return NULL; // 无可用节点
}

/*
 *@brief 链表节点内存池释放函数
 *@param List 链表句柄指针
 *@param node 释放的节点指针
 */
static void FreeNode(ListHandle_t *List, ListNode_t *node)
{
    int index = node - List->NodePool; // 计算节点在池中的索引
    if (index >= 0 && index < List->PoolSize)
    {
        List->NodePool[index].IsUsed = false;
    }
}

/*
 * =======================链表操作函数=======================
 */

/*
 * @brief 创建链表
 * @param List 链表句柄指针
 * @param NodePool 节点内存池数组指针
 * @param PoolSize 内存池大小
 */
void List_Create(ListHandle_t *List, ListNode_t *NodePool, uint16_t PoolSize)
{
    List->NodePool = NodePool;
    List->Head = NULL;
    List->PoolSize = PoolSize;
    List->NodeCount = 0;
}

/*
 * @brief 添加节点
 * @param List 链表句柄指针
 * @param ID 节点ID
 * @return 0: 添加成功，1: ID已存在，2: 链表已满，3: 无可用内存
 */
uint8_t List_AddNode(ListHandle_t *List, uint8_t ID, uint32_t Value)
{
    if (List->NodeCount >= List->PoolSize)
    {
        return 2; // 链表已满
    }

    if (List_IsExistID(List, ID))
    {
        return 1; // ID 已存在
    }
    ListNode_t *NewNode = AllocateNode(List);
    if (NewNode == NULL)
    {
        return 3; // 无可用内存
    }

    NewNode->ID = ID;
    NewNode->Value = Value;
    NewNode->Next = NULL;

    if (List->Head == NULL)
    {
        List->Head = NewNode;
    }
    else
    {
        ListNode_t *Current = List->Head;
        while (Current->Next != NULL)
        {
            Current = Current->Next;
        }
        Current->Next = NewNode;
    }

    List->NodeCount++;
    return 0; // 添加成功
}

/*
 * @brief 删除节点
 * @param List 链表句柄指针
 * @param ID 节点ID
 * @return 0: 删除成功，1: 未找到节点，2: 链表为空
 */
uint8_t List_DeleteNode(ListHandle_t *List, uint8_t ID)
{
    ListNode_t *Current = List->Head;
    ListNode_t *Previous = NULL;
    if (List->NodeCount == 0)
    {
        return 2; // 链表为空
    }
    while (Current != NULL)
    {
        if (Current->ID == ID)
        {
            if (Previous == NULL)
            {
                List->Head = Current->Next;
            }
            else
            {
                Previous->Next = Current->Next;
            }
            FreeNode(List, Current);
            List->NodeCount--;
            return 0; // 删除成功
        }
        Previous = Current;
        Current = Current->Next;
    }

    return 1; // 未找到节点
}

/*
 * @brief 查找节点
 * @param List 链表句柄指针
 * @param ID 节点ID
 * @return 节点指针，未找到返回NULL
 */
bool List_IsExistID(ListHandle_t *List, uint8_t ID)
{
    ListNode_t *Current = List->Head;
    while (Current != NULL)
    {
        if (Current->ID == ID)
        {
            return true;
        }
        Current = Current->Next;
    }
    return false;
}
