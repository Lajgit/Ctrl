#ifndef __APP_LIST_H__
#define __APP_LIST_H__

#include "main.h"
#include "stdbool.h"

typedef struct Node
{
    uint8_t ID;
    uint32_t Value;
    bool IsUsed;
    struct Node *Next;
} ListNode_t;

typedef struct
{
    ListNode_t *NodePool;
    ListNode_t *Head;
    uint16_t PoolSize;
    uint16_t NodeCount;
} ListHandle_t;

void List_Create(ListHandle_t *List, ListNode_t *NodePool, uint16_t PoolSize);
uint8_t List_AddNode(ListHandle_t *List, uint8_t ID,uint32_t Value);
uint8_t List_DeleteNode(ListHandle_t *List, uint8_t ID);
bool List_IsExistID(ListHandle_t *List, uint8_t ID);
uint8_t List_ChangeNodeValue(ListHandle_t *List, uint8_t ID, uint32_t NewValue);
void List_Clear(ListHandle_t *List);

#endif